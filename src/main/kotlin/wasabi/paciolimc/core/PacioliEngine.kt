package wasabi.paciolimc.engine

import wasabi.paciolimc.logger.PacioliLog
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Pacioli Engine Alpha 1.3.1 (RC1)
 * A high-performance spatial indexer for Minecraft.
 * * DESIGN PRINCIPLES:
 * 1. Zero-Allocation Hot Paths
 * 2. Bit-Safe Spatial Packing
 * 3. Library-Grade Guardrails (Radius & Result Caps)
 */
class PacioliEngine {

    private val cellSize = 16.0
    private val ENTITY_CAP = 1024 
    
    // Library Guardrails (Mechanical Safety)
    private val MAX_QUERY_RADIUS = 64.0 
    private val MAX_RESULTS_PER_QUERY = 2048 
    
    private val entityCells = ConcurrentHashMap<Long, MutableList<Entity>>()
    private val lastEntityCell = ConcurrentHashMap<Int, Long>()
    private val bufferCache = ConcurrentHashMap<Long, FloatArray>()

    /**
     * Updates an entity's position in the spatial grid.
     * Note: In Alpha 1.4, movement thresholds will be added to reduce lock contention.
     */
    fun updateEntity(entity: Entity) {
        if (entity.isRemoved) {
            purgeEntity(entity)
            return
        }

        val entityId = entity.id
        val currentKey = packCell(entity.x, entity.y, entity.z)
        val oldKey = lastEntityCell[entityId]

        if (currentKey != oldKey) {
            if (oldKey != null) removeFromCell(oldKey, entity)

            val list = entityCells.computeIfAbsent(currentKey) { mutableListOf() }

            synchronized(list) {
                if (list.size < ENTITY_CAP) {
                    list.add(entity)
                    lastEntityCell[entityId] = currentKey
                } else {
                    PacioliLog.warn("ENGINE", "Cell $currentKey saturated at $ENTITY_CAP. Dropping ID: $entityId")
                }
            }
        }
    }

    /**
     * Performs a spherical proximity query.
     * WARNING: Returns a partial result set if MAX_RESULTS_PER_QUERY is exceeded.
     */
    fun getEntitiesInRange(origin: Vec3, radius: Double, result: MutableList<Entity>) {
        result.clear()

        val safeRadius = radius.coerceIn(0.0, MAX_QUERY_RADIUS)
        if (safeRadius != radius) {
            PacioliLog.warn("ENGINE", "Query radius $radius clamped to $MAX_QUERY_RADIUS")
        }
        
        val rSqr = safeRadius * safeRadius
        
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
                    
                    synchronized(cellList) {
                        for (i in 0 until cellList.size) {
                            if (result.size >= MAX_RESULTS_PER_QUERY) {
                                PacioliLog.warn("ENGINE", "Query saturated. Returning partial set (Cap: $MAX_RESULTS_PER_QUERY)")
                                return 
                            }

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
            if (list.isEmpty()) {
                entityCells.remove(key, list) 
                bufferCache.remove(key)
            }
        }
    }

    fun purgeEntity(entity: Entity) {
        val key = lastEntityCell.remove(entity.id)
        if (key != null) removeFromCell(key, entity)
    }

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

    fun safetySweep() {
        val startTime = System.nanoTime()
        val activeIds = HashSet<Int>(lastEntityCell.size)
        
        val cellIterator = entityCells.entries.iterator()
        while (cellIterator.hasNext()) {
            val entry = cellIterator.next()
            val list = entry.value
            synchronized(list) {
                list.removeIf { entity -> 
                    val removed = entity.isRemoved
                    if (!removed) activeIds.add(entity.id)
                    removed
                }
                if (list.isEmpty()) {
                    bufferCache.remove(entry.key)
                    cellIterator.remove()
                }
            }
        }
        lastEntityCell.entries.removeIf { it.key !in activeIds }
        
        val duration = (System.nanoTime() - startTime) / 1_000_000.0
        PacioliLog.metric("SAFETY_SWEEP", duration, activeIds.size)
    }

    fun clearCache() {
        entityCells.clear()
        lastEntityCell.clear()
        bufferCache.clear()
        PacioliLog.info("ENGINE", "Memory state fully reset.")
    }
}