package wasabi.paciolimc.engine

import wasabi.paciolimc.api.PacioliAPI
import wasabi.paciolimc.logger.PacioliLog
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Per-[ServerLevel] deterministic spatial + state engine.
 *
 * Tick pipeline: **snapshot** (all entities) → **derive** (movement + health) → **commit** (16³ cell grid + chunk-column grid).
 * Queries after tick use cached [TrackingMetadata] only for geometry (no live [Entity] position reads).
 *
 * Mutate only on the **server game thread**.
 */
class PacioliEngine private constructor(val level: ServerLevel) {

    private val cellSize = 16.0
    private val entityCap = 1024
    private val maxQueryRadius = 64.0
    private val maxResultsPerQuery = 2048

    /** Blocks per tick threshold for [MovementBits.FAST] at ~20 TPS. */
    private val fastSpeedPerTick = 0.45
    private val fallDyThreshold = 0.08

    private var tickSerial: Long = 0L

    private val tracking = HashMap<Int, TrackingMetadata>(2048)
    private val entityCells = HashMap<Long, ArrayList<Int>>(512)
    private val entityCellIndex = HashMap<Int, CellSlot>(2048)

    /** Secondary index: horizontal chunk column (16×16), all Y — for AI locality queries. */
    private val chunkEntities = HashMap<Long, ArrayList<Int>>(256)
    private val entityChunkIndex = HashMap<Int, ChunkSlot>(2048)

    private data class CellSlot(var cellKey: Long, var index: Int)
    private data class ChunkSlot(var chunkKey: Long, var index: Int)

    private val idScratch = ArrayList<Int>(maxResultsPerQuery)

    companion object {

        private val byLevel = ConcurrentHashMap<ServerLevel, PacioliEngine>()

        @JvmStatic
        fun forLevel(level: ServerLevel): PacioliEngine =
            byLevel.computeIfAbsent(level) { PacioliEngine(level) }

        @JvmStatic
        fun tick(level: ServerLevel) {
            forLevel(level).tickInternal()
        }

        @JvmStatic
        fun removeLevel(level: ServerLevel) {
            byLevel.remove(level)?.clearCache()
        }
    }

    fun currentTickSerial(): Long = tickSerial

    fun trackedCount(): Int = tracking.size

    fun spatialCellCount(): Int = entityCells.size

    fun chunkColumnCount(): Int = chunkEntities.size

    fun trackingMetadata(entityId: Int): TrackingMetadata? = tracking[entityId]

    /** Immutable copy for other mods; `null` if unknown this tick. */
    fun trackingSnapshot(entityId: Int): EntityTrackingSnapshot? {
        val m = tracking[entityId] ?: return null
        if (!m.initialized) return null
        return EntityTrackingSnapshot(
            tickSerial = tickSerial,
            prevX = m.prevX,
            prevY = m.prevY,
            prevZ = m.prevZ,
            currX = m.currX,
            currY = m.currY,
            currZ = m.currZ,
            deltaLenSq = m.deltaLenSq,
            movementMask = m.movementMask,
            prevHealth = m.prevHealth,
            health = m.health,
            maxHealth = m.maxHealth,
            healthDelta = m.healthDelta,
            healthTier = m.healthTier,
            healthStateMask = m.healthStateMask,
            spectator = m.spectator,
            isServerPlayer = m.isServerPlayer,
            onGround = m.onGround,
        )
    }

    private fun tickInternal() {
        tickSerial++
        val present = HashSet<Int>(tracking.size + 256)

        // --- Phase 1: snapshot (read entity truth once; promote prev ← curr) ---
        for (entity in level.getAllEntities()) {
            if (entity.isRemoved) continue
            val id = entity.id
            present.add(id)
            val meta = tracking.getOrPut(id) { TrackingMetadata() }
            snapshotEntity(entity, meta)
        }

        // --- Phase 2: derive (movement + health; single write per entity) ---
        for (id in present) {
            val meta = tracking[id] ?: continue
            deriveMovement(meta)
            deriveHealth(meta)
        }

        // --- Phase 3: commit spatial indices from cached curr* only ---
        for (id in present) {
            val meta = tracking[id] ?: continue
            val newCell = packCell(meta.currX, meta.currY, meta.currZ)
            val cellSlot = entityCellIndex[id]
            if (cellSlot == null) {
                addToCell(id, newCell)
            } else if (cellSlot.cellKey != newCell) {
                removeFromCell(id)
                addToCell(id, newCell)
            }

            val newChunk = chunkKeyFromWorld(meta.currX, meta.currZ)
            val chSlot = entityChunkIndex[id]
            if (chSlot == null) {
                addToChunk(id, newChunk)
            } else if (chSlot.chunkKey != newChunk) {
                removeFromChunk(id)
                addToChunk(id, newChunk)
            }
        }

        // --- Purge removed entities ---
        val iter = tracking.keys.iterator()
        while (iter.hasNext()) {
            val id = iter.next()
            if (id !in present) {
                iter.remove()
                removeFromCell(id)
                removeFromChunk(id)
                PacioliAPI.purgeId(id)
            }
        }

        PacioliAPI.dispatchPostTickHooks(level)
    }

    private fun snapshotEntity(entity: Entity, meta: TrackingMetadata) {
        val pos = entity.position()
        if (!meta.initialized) {
            meta.currX = pos.x
            meta.currY = pos.y
            meta.currZ = pos.z
            meta.prevX = pos.x
            meta.prevY = pos.y
            meta.prevZ = pos.z
            meta.initialized = true
        } else {
            meta.prevX = meta.currX
            meta.prevY = meta.currY
            meta.prevZ = meta.currZ
            meta.currX = pos.x
            meta.currY = pos.y
            meta.currZ = pos.z
        }

        meta.isServerPlayer = entity is ServerPlayer
        meta.spectator = entity is ServerPlayer && entity.isSpectator

        if (entity is LivingEntity) {
            val nh = entity.health
            val nm = entity.maxHealth
            if (!meta.hasHealthSnapshot) {
                meta.prevHealth = nh
                meta.health = nh
                meta.maxHealth = nm
                meta.hasHealthSnapshot = true
            } else {
                meta.prevHealth = meta.health
                meta.health = nh
                meta.maxHealth = nm
            }
            meta.onGround = entity.onGround
        } else {
            meta.prevHealth = 0f
            meta.health = 0f
            meta.maxHealth = 0f
            meta.hasHealthSnapshot = false
            meta.onGround = true
        }
    }

    private fun deriveMovement(meta: TrackingMetadata) {
        val dx = meta.currX - meta.prevX
        val dy = meta.currY - meta.prevY
        val dz = meta.currZ - meta.prevZ
        val horizSq = dx * dx + dz * dz
        val anySq = horizSq + dy * dy
        meta.deltaLenSq = anySq

        val speed = sqrt(anySq)
        var mask = 0

        if (anySq <= 1.0e-8) {
            mask = mask or MovementBits.STATIONARY
        } else {
            mask = mask or MovementBits.MOVING
            if (speed >= fastSpeedPerTick) mask = mask or MovementBits.FAST
        }

        if (!meta.onGround) mask = mask or MovementBits.AIRBORNE
        if (dy < -fallDyThreshold) mask = mask or MovementBits.FALLING

        val hLen = sqrt(horizSq)
        if (hLen > 1.0e-4) {
            val nx = dx / hLen
            val nz = dz / hLen
            if (meta.hasLastHorizDir) {
                val dot = meta.lastHorizDirX * nx + meta.lastHorizDirZ * nz
                if (dot < 0.85) mask = mask or MovementBits.DIR_CHANGE
            }
            meta.lastHorizDirX = nx
            meta.lastHorizDirZ = nz
            meta.hasLastHorizDir = true
        }

        meta.movementMask = mask
    }

    private fun deriveHealth(meta: TrackingMetadata) {
        if (meta.maxHealth <= 0f) {
            meta.healthDelta = 0f
            meta.healthTier = HealthTier.NONE
            meta.healthStateMask = 0
            return
        }

        val delta = meta.health - meta.prevHealth
        meta.healthDelta = delta

        var flags = 0
        if (delta < -1.0e-3f) flags = flags or HealthBits.DAMAGE
        if (delta > 1.0e-3f) flags = flags or HealthBits.HEAL

        val ratio = meta.health / meta.maxHealth
        meta.healthTier = when {
            ratio < 0.15f -> HealthTier.NEAR_DEATH
            ratio < 0.35f -> HealthTier.CRITICAL
            ratio < 0.85f -> HealthTier.INJURED
            else -> HealthTier.HEALTHY
        }

        meta.healthStateMask = flags
    }

    private fun addToCell(entityId: Int, cellKey: Long) {
        val list = entityCells.getOrPut(cellKey) { ArrayList(32) }
        if (list.size >= entityCap) {
            PacioliLog.warnRateLimited("ENGINE", "cell_sat_$cellKey", "Cell saturation at $cellKey; dropped id=$entityId")
            return
        }
        val idx = list.size
        list.add(entityId)
        entityCellIndex[entityId] = CellSlot(cellKey, idx)
    }

    private fun removeFromCell(entityId: Int) {
        val slot = entityCellIndex.remove(entityId) ?: return
        val list = entityCells[slot.cellKey] ?: return
        val lastIdx = list.lastIndex
        if (slot.index != lastIdx) {
            val swapped = list[lastIdx]
            list[slot.index] = swapped
            entityCellIndex[swapped]?.index = slot.index
        }
        list.removeAt(lastIdx)
        if (list.isEmpty()) {
            entityCells.remove(slot.cellKey)
        }
    }

    private fun addToChunk(entityId: Int, chunkKey: Long) {
        val list = chunkEntities.getOrPut(chunkKey) { ArrayList(64) }
        if (list.size >= entityCap * 4) {
            PacioliLog.warnRateLimited("ENGINE", "chunk_sat_$chunkKey", "Chunk column saturation at $chunkKey; dropped id=$entityId")
            return
        }
        val idx = list.size
        list.add(entityId)
        entityChunkIndex[entityId] = ChunkSlot(chunkKey, idx)
    }

    private fun removeFromChunk(entityId: Int) {
        val slot = entityChunkIndex.remove(entityId) ?: return
        val list = chunkEntities[slot.chunkKey] ?: return
        val lastIdx = list.lastIndex
        if (slot.index != lastIdx) {
            val swapped = list[lastIdx]
            list[slot.index] = swapped
            entityChunkIndex[swapped]?.index = slot.index
        }
        list.removeAt(lastIdx)
        if (list.isEmpty()) {
            chunkEntities.remove(slot.chunkKey)
        }
    }

    /**
     * Narrow-phase sphere using **only** [TrackingMetadata] positions (no live entity reads).
     */
    fun collectEntityIdsInRange(origin: Vec3, radius: Double, result: MutableList<Int>) {
        result.clear()
        val safeRadius = radius.coerceIn(0.0, maxQueryRadius)
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
                    val list = entityCells[key] ?: continue
                    for (i in 0 until list.size) {
                        if (result.size >= maxResultsPerQuery) return
                        val id = list[i]
                        val meta = tracking[id] ?: continue
                        val dx = meta.currX - origin.x
                        val dy = meta.currY - origin.y
                        val dz = meta.currZ - origin.z
                        if (dx * dx + dy * dy + dz * dz <= rSqr) {
                            result.add(id)
                        }
                    }
                }
            }
        }
    }

    /**
     * Broad-phase: all ids in cells intersecting the query cube (no sphere cull). Metadata-only.
     */
    fun collectCandidateEntityIds(origin: Vec3, radius: Double, result: MutableList<Int>) {
        collectEntityIdsInCellCube(origin, radius.coerceIn(0.0, maxQueryRadius), result)
    }

    /**
     * Broad-phase then resolve [Entity] for listeners/filters that require instances.
     * Distance checks should use [distanceSqBetweenTracked] or [collectEntityIdsInRange] instead of live positions.
     */
    fun getCandidateBufferBroadPhase(origin: Vec3, radius: Double, result: MutableList<Entity>) {
        result.clear()
        collectCandidateEntityIds(origin, radius, idScratch)
        for (i in 0 until idScratch.size) {
            if (result.size >= maxResultsPerQuery) return
            val id = idScratch[i]
            val e = level.getEntity(id) ?: continue
            if (!e.isRemoved) result.add(e)
        }
    }

    fun distanceSqBetweenTracked(aId: Int, bId: Int): Double? {
        val ma = tracking[aId] ?: return null
        val mb = tracking[bId] ?: return null
        if (!ma.initialized || !mb.initialized) return null
        val dx = ma.currX - mb.currX
        val dy = ma.currY - mb.currY
        val dz = ma.currZ - mb.currZ
        return dx * dx + dy * dy + dz * dz
    }

    /** Observer position from cache; falls back to live only before first tick. */
    fun getObserverPosition(observer: Entity): Vec3 {
        val id = observer.id
        val meta = tracking[id]
        return if (meta != null && meta.initialized) {
            Vec3(meta.currX, meta.currY, meta.currZ)
        } else {
            observer.position()
        }
    }

    /**
     * Legacy path: fills [Entity] by resolving ids after metadata-only range cull.
     * Prefer [collectEntityIdsInRange] for hot AI (zero entity touches until you apply behavior).
     */
    fun getEntitiesInRange(origin: Vec3, radius: Double, result: MutableList<Entity>) {
        result.clear()
        collectEntityIdsInRange(origin, radius, idScratch)
        for (i in 0 until idScratch.size) {
            if (result.size >= maxResultsPerQuery) return
            val id = idScratch[i]
            val e = level.getEntity(id) ?: continue
            if (!e.isRemoved) result.add(e)
        }
    }

    /**
     * All tracked ids in a horizontal chunk column (16×16 world XZ), any Y.
     */
    fun collectEntityIdsInChunkColumn(chunkX: Int, chunkZ: Int, result: MutableList<Int>) {
        result.clear()
        val key = packChunk(chunkX, chunkZ)
        val list = chunkEntities[key] ?: return
        for (i in 0 until list.size) {
            if (result.size >= maxResultsPerQuery) return
            result.add(list[i])
        }
    }

    /**
     * Chunk-column neighborhood: Manhattan or Chebyshev radius in chunk units.
     */
    fun collectEntityIdsInChunkRadius(centerChunkX: Int, centerChunkZ: Int, chunkRadius: Int, result: MutableList<Int>) {
        result.clear()
        val r = chunkRadius.coerceIn(0, 32)
        for (dx in -r..r) {
            for (dz in -r..r) {
                val key = packChunk(centerChunkX + dx, centerChunkZ + dz)
                val list = chunkEntities[key] ?: continue
                for (i in 0 until list.size) {
                    if (result.size >= maxResultsPerQuery) return
                    result.add(list[i])
                }
            }
        }
    }

    fun purgeEntity(entity: Entity) {
        val id = entity.id
        tracking.remove(id)
        removeFromCell(id)
        removeFromChunk(id)
        PacioliAPI.purgeId(id)
    }

    fun safetySweep() {
        val bad = ArrayList<Int>(32)
        for ((id, slot) in entityCellIndex) {
            val e = level.getEntity(id)
            if (e == null || e.isRemoved) {
                bad.add(id)
                continue
            }
            val meta = tracking[id]
            if (meta == null || !meta.initialized) {
                bad.add(id)
                continue
            }
            val expectedCell = packCell(meta.currX, meta.currY, meta.currZ)
            if (slot.cellKey != expectedCell) bad.add(id)
        }
        for (id in bad) {
            removeFromCell(id)
            removeFromChunk(id)
            tracking.remove(id)
            PacioliAPI.purgeId(id)
        }
    }

    fun clearCache() {
        tracking.clear()
        entityCells.clear()
        entityCellIndex.clear()
        chunkEntities.clear()
        entityChunkIndex.clear()
    }

    private fun collectEntityIdsInCellCube(origin: Vec3, safeRadius: Double, out: ArrayList<Int>) {
        out.clear()
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
                    val list = entityCells[key] ?: continue
                    for (i in 0 until list.size) {
                        if (out.size >= maxResultsPerQuery) return
                        out.add(list[i])
                    }
                }
            }
        }
    }

    private fun chunkKeyFromWorld(x: Double, z: Double): Long {
        val cx = floor(x / cellSize).toInt()
        val cz = floor(z / cellSize).toInt()
        return packChunk(cx, cz)
    }

    private fun packChunk(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

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
