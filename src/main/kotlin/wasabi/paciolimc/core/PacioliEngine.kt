package wasabi.paciolimc.engine

import wasabi.paciolimc.logger.PacioliLog
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

class PacioliEngine(val world: ServerLevel) {

    private val cellSize = 16 // Each cell 16x16x16 blocks
    private val entityCells: MutableMap<Long, MutableList<LivingEntity>> = mutableMapOf()
    private val lastEntityCell: MutableMap<LivingEntity, Long> = mutableMapOf()

    // Center active tracking around a player (optional)
    private var activeCenter: Vec3? = null
    private var activeRadius = 128.0 // blocks

    // --- Tick: only update entities that moved across cells ---
    fun tick() {
        // Remove dead or removed entities
        lastEntityCell.keys.removeIf { entity ->
            if (!entity.isAlive || entity.isRemoved) {
                val lastCell = lastEntityCell[entity]
                lastCell?.let { entityCells[it]?.remove(entity) }
                true
            } else false
        }

        // Update movement-driven cells
        for (entity in world.entities) {
            if (entity !is LivingEntity) continue
            val pos = entity.position()

            // Skip entities outside active radius
            activeCenter?.let { center ->
                if (pos.distanceToSqr(center) > activeRadius * activeRadius) continue
            }

            val cell = packCell(pos)
            val lastCell = lastEntityCell[entity]

            if (cell != lastCell) {
                lastCell?.let { entityCells[it]?.remove(entity) }
                entityCells.computeIfAbsent(cell) { mutableListOf() }.add(entity)
                lastEntityCell[entity] = cell

                if (PacioliLog.isDebugEnabled) {
                    PacioliLog.debug("Entity ${entity.name.string} moved to cell $cell")
                }
            }
        }
    }

    // --- Query entities in range ---
    fun entitiesInRange(
        pos: Vec3,
        radius: Double,
        result: MutableList<LivingEntity> = mutableListOf()
    ): List<LivingEntity> {
        result.clear()
        val radiusSquared = radius * radius

        val minX = floor((pos.x - radius) / cellSize).toInt()
        val maxX = floor((pos.x + radius) / cellSize).toInt()
        val minY = floor((pos.y - radius) / cellSize).toInt()
        val maxY = floor((pos.y + radius) / cellSize).toInt()
        val minZ = floor((pos.z - radius) / cellSize).toInt()
        val maxZ = floor((pos.z + radius) / cellSize).toInt()

        for (x in minX..maxX) {
            val xBits = (x and 0xFFFFF).toLong() shl 40
            for (y in minY..maxY) {
                val yBits = (y and 0xFFFFF).toLong() shl 20
                for (z in minZ..maxZ) {
                    val cellKey = xBits or yBits or (z and 0xFFFFF).toLong()
                    entityCells[cellKey]?.forEach { e ->
                        if (e.position().distanceToSqr(pos) <= radiusSquared) {
                            result.add(e)
                        }
                    }
                }
            }
        }

        return result
    }

    // --- Utility: pack 3D cell coordinates into a single Long ---
    private fun packCell(pos: Vec3): Long {
        val x = floor(pos.x / cellSize).toInt() and 0xFFFFF
        val y = floor(pos.y / cellSize).toInt() and 0xFFFFF
        val z = floor(pos.z / cellSize).toInt() and 0xFFFFF
        return (x.toLong() shl 40) or (y.toLong() shl 20) or z.toLong()
    }

    private fun packCell(x: Int, y: Int, z: Int): Long {
        val xx = x and 0xFFFFF
        val yy = y and 0xFFFFF
        val zz = z and 0xFFFFF
        return (xx.toLong() shl 40) or (yy.toLong() shl 20) or zz.toLong()
    }

    // --- Optional utilities ---
    fun distanceToPlayer(pos: Vec3, player: Player): Double {
        return pos.distanceTo(player.position())
    }

    fun playerInRange(pos: Vec3, player: Player, radius: Double): Boolean {
        return pos.distanceToSqr(player.position()) <= radius * radius
    }

    // --- Active area management ---
    fun setActiveArea(center: Vec3, radius: Double) {
        activeCenter = center
        activeRadius = radius
    }

    fun clearActiveArea() {
        activeCenter = null
    }
}