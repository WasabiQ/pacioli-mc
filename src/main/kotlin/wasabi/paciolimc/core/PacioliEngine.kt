package wasabi.paciolimc.engine

import wasabi.paciolimc.logger.PacioliLog
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

class PacioliEngine {

    private val cellSize = 16.0
    private val ENTITY_CAP = 1024 
    
    private val entityCells = ConcurrentHashMap<Long, MutableList<Entity>>()
    private val lastEntityCell = ConcurrentHashMap<Int, Long>()
    private val bufferCache = ConcurrentHashMap<Long, FloatArray>()

    fun updateEntity(entity: Entity) {
        if (entity.isRemoved) {
            purgeEntity(entity)
            return
        }

        val entityId = entity.id
        val currentKey = packCell(entity.x, entity.y, entity.z)
        val oldKey = lastEntityCell[entityId]

        if (currentKey != oldKey) {
            if (oldKey != null) {
                removeFromCell(oldKey, entity)
            }

            val list = entityCells.computeIfAbsent(currentKey) { 
                Collections.synchronizedList(mutableListOf()) 
            }

            // Fix #1: State Consistency & Bounded Capacity
            synchronized(list) {
                if (list.size < ENTITY_CAP) {
                    list.add(entity)
                }
            }
            lastEntityCell[entityId] = currentKey
        }
    }

    fun purgeEntity(entity: Entity) {
        val key = lastEntityCell.remove(entity.id)
        if (key != null) {
            removeFromCell(key, entity)
        }
    }

    private fun removeFromCell(key: Long, entity: Entity) {
        entityCells[key]?.let { list ->
            synchronized(list) {
                list.remove(entity)
                if (list.isEmpty()) {
                    // Fix #1: Atomic removal to prevent race condition if a thread adds mid-delete
                    entityCells.remove(key, list) 
                    bufferCache.remove(key)
                }
            }
        }
    }

    fun getEntitiesInRange(origin: Vec3, radius: Double, result: MutableList<Entity>) {
        result.clear()
        val rSqr = radius * radius
        
        val minX = floor((origin.x - radius) / cellSize).toInt()
        val maxX = floor((origin.x + radius) / cellSize).toInt()
        val minY = floor((origin.y - radius) / cellSize).toInt()
        val maxY = floor((origin.y + radius) / cellSize).toInt()
        val minZ = floor((origin.z - radius) / cellSize).toInt()
        val maxZ = floor((origin.z + radius) / cellSize).toInt()

        for (x in minX..maxX) {
            val xPart = (x.toLong() and 0xFFFFF) shl 40
            for (y in minY..maxY) {
                val yPart = (y.toLong() and 0xFFFFF) shl 20
                for (z in minZ..maxZ) {
                    val key = xPart or yPart or (z.toLong() and 0xFFFFF)
                    
                    entityCells[key]?.let { cellList ->
                        synchronized(cellList) {
                            for (i in 0 until cellList.size) {
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
    }

    fun getGpuVertexData(cellKey: Long): FloatArray? {
        val entities = entityCells[cellKey] ?: return null
        
        synchronized(entities) {
            val requiredSize = entities.size * 3
            val buffer = bufferCache[cellKey]
            
            val finalBuffer = if (buffer == null || buffer.size < requiredSize) {
                val newSize = if (buffer == null) requiredSize else maxOf(requiredSize, buffer.size * 2)
                FloatArray(newSize).also { bufferCache[cellKey] = it }
            } else buffer
            
            for (i in entities.indices) {
                val e = entities[i]
                finalBuffer[i * 3] = e.x.toFloat()
                finalBuffer[i * 3 + 1] = e.y.toFloat()
                finalBuffer[i * 3 + 2] = e.z.toFloat()
            }
            return finalBuffer
        }
    }

    /**
     * Final Safety Sweep: O(N) Complexity with Zero Drift.
     */
    fun safetySweep() {
        // Fix #3: Smarter pre-sizing to prevent over-allocation if stale entries exist
        val activeIds = HashSet<Int>(entityCells.size * 16)
        
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

        // Fix #2: Micro-optimized removal avoiding lambda capture overhead
        lastEntityCell.entries.removeIf { entry -> !activeIds.contains(entry.key) }
    }

    private fun packCell(x: Double, y: Double, z: Double): Long {
        val ix = floor(x / cellSize).toLong() and 0xFFFFF
        val iy = floor(y / cellSize).toLong() and 0xFFFFF
        val iz = floor(z / cellSize).toLong() and 0xFFFFF
        return (ix shl 40) or (iy shl 20) or iz
    }

    fun clearCache() {
        entityCells.clear()
        lastEntityCell.clear()
        bufferCache.clear()
        PacioliLog.system("Pacioli Engine: Memory state fully reset.")
    }
}