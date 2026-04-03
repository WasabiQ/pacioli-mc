package wasabi.paciolimc.engine

import wasabi.paciolimc.api.PacioliAPI
import wasabi.paciolimc.logger.PacioliLog
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class PacioliEngine {

    private val cellSize = 16.0
    private val ENTITY_CAP = 1024 
    
    private val MAX_QUERY_RADIUS = 64.0 
    private val MAX_RESULTS_PER_QUERY = 2048 
    
    // Using ConcurrentHashMap for the grid. Lists inside are synchronized manually.
    private val entityCells = ConcurrentHashMap<Long, MutableList<Entity>>()
    private val lastEntityCell = ConcurrentHashMap<Int, Long>()

    /**
     * Alpha 1.4.2 Change: Integrated PacioliAPI.purgeId to prevent logic leaks.
     */
    fun updateEntity(entity: Entity) {
        val entityId = entity.id

        if (entity.isRemoved) {
            purgeEntity(entity)
            return
        }

        val currentKey = packCell(entity.x, entity.y, entity.z)
        val oldKey = lastEntityCell[entityId]

        // ⚡ Optimization: Skip if they haven't crossed a 16m cell boundary.
        if (currentKey == oldKey) return

        if (oldKey != null) removeFromCell(oldKey, entity)

        val list = entityCells.computeIfAbsent(currentKey) { ArrayList() }

        synchronized(list) {
            if (list.size < ENTITY_CAP) {
                list.add(entity)
                lastEntityCell[entityId] = currentKey
            } else {
                PacioliLog.warn("ENGINE", "Cell saturation at $currentKey. ID: $entityId dropped.")
            }
        }
    }

    fun getEntitiesInRange(origin: Vec3, radius: Double, result: MutableList<Entity>) {
        result.clear()
        val safeRadius = radius.coerceIn(0.0, MAX_QUERY_RADIUS)
        val rSqr = safeRadius * safeRadius
        
        // Define the search cube
        val minX = floor((origin.x - safeRadius) / cellSize).toInt()
        val maxX = floor((origin.x + safeRadius) / cellSize).toInt()
        val minY = floor((origin.y - safeRadius) / cellSize).toInt()
        val maxY = floor((origin.y + safeRadius) / cellSize).toInt()
        val minZ = floor((origin.z - safeRadius) / cellSize).toInt()
        val maxZ = floor((origin.z + safeRadius) / cellSize).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val key = packFromInts(x, y, z)
                    val cellList = entityCells[key] ?: continue
                    
                    // 🛡️ CRITICAL: Synchronize the READ to prevent ConcurrentModification
                    synchronized(cellList) {
                        for (i in 0 until cellList.size) {
                            if (result.size >= MAX_RESULTS_PER_QUERY) return 
                            
                            val e = cellList[i]
                            val dx = e.x - origin.x
                            val dy = e.y - origin.y
                            val dz = e.z - origin.z
                            
                            if (dx * dx + dy * dy + dz * dz <= rSqr) {
                                result.add(e)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun removeFromCell(key: Long, entity: Entity) {
        val list = entityCells[key] ?: return
        synchronized(list) {
            list.remove(entity)
            if (list.isEmpty()) entityCells.remove(key)
        }
    }

    fun purgeEntity(entity: Entity) {
        val entityId = entity.id
        val key = lastEntityCell.remove(entityId)
        if (key != null) removeFromCell(key, entity)
        
        // 🎯 THE MISSING LINK: Sync with the Boss/Intelligence API
        PacioliAPI.purgeId(entityId)
    }

    /**
     * Bit-Packing logic (unchanged, but very solid)
     */
    private fun packFromInts(x: Int, y: Int, z: Int): Long {
        return ((x.toLong() and 0xFFFFF) shl 40) or
               ((y.toLong() and 0xFFFFF) shl 20) or
               (z.toLong() and 0xFFFFF)
    }

    private fun packCell(x: Double, y: Double, z: Double): Long {
        return packFromInts(
            floor(x / cellSize).toInt(),
            floor(y / cellSize).toInt(),
            floor(z / cellSize).toInt()
        )
    }
}