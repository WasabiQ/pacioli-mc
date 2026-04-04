package wasabi.paciolimc.client

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import wasabi.paciolimc.commands.PacioliCommands
import wasabi.paciolimc.engine.PacioliEngine
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

/**
 * Client-side diagnostic trails (integrated server only). Uses the server [PacioliEngine] for queries (MC **26.1**).
 */
object Breadcrumbs {
    private val mc: Minecraft get() = Minecraft.getInstance()

    private val queryBuffer = ArrayList<Entity>(64)
    private val activeIds = HashSet<Int>(64)

    private var frontBuffer = IntArray(64)
    private var backBuffer = IntArray(64)
    private var activeCount = 0

    private const val maxTrackedEntities = 50
    private const val maxSections = 80
    private const val sectionLenSq = 0.25
    private const val teleportThresholdSq = 100.0

    private val trails = HashMap<Int, ArrayDeque<BreadcrumbSection>>()
    private val lastPositions = HashMap<Int, Vec3>()

    private fun serverEngine(): PacioliEngine? {
        if (!mc.isLocalServer) return null
        val server = mc.singleplayerServer ?: return null
        val player = mc.player ?: return null
        val level = player.level()
        val sl = server.getLevel(level.dimension()) ?: return null
        return PacioliEngine.forLevel(sl)
    }

    fun onClientTick() {
        if (!mc.isLocalServer || !PacioliCommands.breadcrumbsEnabled) {
            if (trails.isNotEmpty()) clear()
            return
        }

        val player = mc.player ?: return
        val eng = serverEngine() ?: return

        queryBuffer.clear()
        activeIds.clear()
        eng.getEntitiesInRange(player.position(), 24.0, queryBuffer)

        var count = 0
        val limit = minOf(queryBuffer.size, maxTrackedEntities)

        for (i in 0 until limit) {
            val entity = queryBuffer[i]
            if (entity === player) continue

            val id = entity.id

            if (count < backBuffer.size) {
                backBuffer[count++] = id
                activeIds.add(id)
            }

            val currentPos = entity.position()
            val lastPos = lastPositions[id]

            if (lastPos != null) {
                val distSq = currentPos.distanceToSqr(lastPos)
                if (distSq > teleportThresholdSq) {
                    trails[id]?.clear()
                } else if (distSq >= sectionLenSq) {
                    val queue = trails.computeIfAbsent(id) { ArrayDeque() }
                    if (queue.size >= maxSections) queue.poll()
                    queue.add(BreadcrumbSection(lastPos, currentPos))
                    lastPositions[id] = currentPos
                }
            } else {
                lastPositions[id] = currentPos
            }
        }

        val temp = frontBuffer
        frontBuffer = backBuffer
        backBuffer = temp
        activeCount = count

        lastPositions.keys.removeIf { !activeIds.contains(it) }
        trails.keys.removeIf { !activeIds.contains(it) }
    }

    /**
     * World line rendering can be wired from a Fabric world-render callback when you add one; trail data is updated in [onClientTick].
     */
    fun trailCount(): Int = trails.values.sumOf { it.size }

    private fun clear() {
        trails.clear()
        lastPositions.clear()
        activeIds.clear()
        activeCount = 0
    }

    private class BreadcrumbSection(start: Vec3, end: Vec3) {
        val x1 = start.x
        val y1 = start.y
        val z1 = start.z
        val x2 = end.x
        val y2 = end.y
        val z2 = end.z
    }
}
