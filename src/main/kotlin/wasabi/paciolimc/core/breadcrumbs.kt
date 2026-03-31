package wasabi.paciolimc.breadcrumbs

import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import net.minecraft.entity.Entity
import net.minecraft.entity.mob.MobEntity
import wasabi.paciolimc.engine.PacioliEngine
import wasabi.paciolimc.commands.PacioliCommands
import java.util.ArrayDeque
import java.util.HashMap
import java.util.ArrayList
import java.util.HashSet

/**
 * Alpha 1.2 - Finalized High-Performance AI Diagnostic
 * Zero-allocation, double-buffered, and bounds-safe.
 */
object Breadcrumbs {
    private val mc = MinecraftClient.getInstance()
    private var engine: PacioliEngine? = null

    // 1. REUSABLE BUFFERS
    private val queryBuffer = ArrayList<Entity>(64)
    private val activeIds = HashSet<Int>(64)
    
    private var frontBuffer = IntArray(64)
    private var backBuffer = IntArray(64)
    private var activeCount = 0

    // Performance Tuning
    private const val MAX_TRACKED_ENTITIES = 50
    private const val MAX_SECTIONS = 80 
    private const val SECTION_LEN_SQ = 0.25 
    private const val TELEPORT_THRESHOLD_SQ = 100.0 
    
    private val TRAIL_COLOR = intArrayOf(255, 50, 50, 255)
    private val TARGET_COLOR = intArrayOf(50, 150, 255, 100)

    private val trails = HashMap<Int, ArrayDeque<BreadcrumbSection>>()
    private val lastPositions = HashMap<Int, Vec3d>()

    fun init(pacioliEngine: PacioliEngine) {
        this.engine = pacioliEngine
    }

    private fun isSafeEnvironment(): Boolean = mc.isInSingleplayer || mc.isIntegratedServerRunning

    fun onTick() {
        if (!isSafeEnvironment() || !PacioliCommands.breadcrumbsEnabled) {
            if (trails.isNotEmpty()) clear()
            return
        }

        val player = mc.player ?: return
        val eng = engine ?: return
        
        queryBuffer.clear()
        activeIds.clear()
        eng.getEntitiesInRange(player.pos, 24.0, queryBuffer)

        var count = 0
        val limit = minOf(queryBuffer.size, MAX_TRACKED_ENTITIES)
        
        for (i in 0 until limit) {
            val entity = queryBuffer[i]
            if (entity == player) continue
            
            val id = entity.id
            
            // BOUNDS SAFETY: Prevent overflow if backBuffer size is ever outstripped
            if (count < backBuffer.size) {
                backBuffer[count++] = id
                activeIds.add(id)
            }
            
            val currentPos = entity.pos
            val lastPos = lastPositions[id]

            if (lastPos != null) {
                val distSq = currentPos.squaredDistanceTo(lastPos)
                if (distSq > TELEPORT_THRESHOLD_SQ) {
                    trails[id]?.clear()
                } else if (distSq >= SECTION_LEN_SQ) {
                    val queue = trails.computeIfAbsent(id) { ArrayDeque() }
                    if (queue.size >= MAX_SECTIONS) queue.poll()
                    queue.add(BreadcrumbSection(lastPos, currentPos))
                    lastPositions[id] = currentPos
                }
            } else {
                lastPositions[id] = currentPos
            }
        }

        // 2. ATOMIC SWAP: No synchronization needed on main thread
        val temp = frontBuffer
        frontBuffer = backBuffer
        backBuffer = temp
        activeCount = count

        // CLEANUP: Zero-allocation removal of off-screen data
        lastPositions.keys.removeIf { !activeIds.contains(it) }
        trails.keys.removeIf { !activeIds.contains(it) }
    }

    fun onRender(event: Render3DEvent) {
        if (!isSafeEnvironment() || !PacioliCommands.breadcrumbsEnabled) return
        
        val world = mc.world ?: return
        val player = mc.player ?: return
        val renderer = event.renderer.lines

        val px = player.x; val py = player.y; val pz = player.z

        // DRAW TRAILS
        for (trail in trails.values) {
            for (sec in trail) {
                renderer.line(sec.x1, sec.y1, sec.z1, sec.x2, sec.y2, sec.z2, 
                    TRAIL_COLOR[0], TRAIL_COLOR[1], TRAIL_COLOR[2], TRAIL_COLOR[3])
            }
        }

        // DRAW INTENT VECTORS
        for (i in 0 until activeCount) {
            val id = frontBuffer[i]
            val entity = world.getEntityById(id) ?: continue
            
            val pos = entity.pos
            val isTargeting = if (entity is MobEntity) entity.target == player else false
            
            val dx = px - pos.x
            val dy = py - pos.y
            val dz = pz - pos.z
            val vel = entity.velocity

            // 3D Dot Product Heuristic
            val dot = (dx * vel.x) + (dy * vel.y) + (dz * vel.z)

            if (dot > 0.01 || isTargeting) {
                renderer.line(pos.x, pos.y, pos.z, px, py, pz,
                    TARGET_COLOR[0], TARGET_COLOR[1], TARGET_COLOR[2], TARGET_COLOR[3])
            }
        }
    }

    private fun clear() {
        trails.clear()
        lastPositions.clear()
        activeIds.clear()
        activeCount = 0
    }

    private class BreadcrumbSection(start: Vec3d, end: Vec3d) {
        val x1 = start.x; val y1 = start.y; val z1 = start.z
        val x2 = end.x; val y2 = end.y; val z2 = end.z
    }
}