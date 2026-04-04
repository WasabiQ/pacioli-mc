package wasabi.paciolimc.proximity

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import wasabi.paciolimc.engine.PacioliEngine
import java.util.ArrayList

/**
 * Spatial queries with reusable buffers. Player centroid uses **only** cached [TrackingMetadata]
 * (no live [Entity] position reads on the hot path).
 */
class TacticalScanner(private val engine: PacioliEngine) {

    private val idBuffer = ArrayList<Int>(512)
    private val resultBuffer = ArrayList<Entity>()

    fun getPlayerCentroid(origin: Vec3, radius: Double): Vec3? {
        idBuffer.clear()
        engine.collectEntityIdsInRange(origin, radius, idBuffer)

        var tx = 0.0
        var ty = 0.0
        var tz = 0.0
        var count = 0

        for (i in 0 until idBuffer.size) {
            val id = idBuffer[i]
            val meta = engine.trackingMetadata(id) ?: continue
            if (!meta.initialized || !meta.isServerPlayer || meta.spectator) continue
            tx += meta.currX
            ty += meta.currY
            tz += meta.currZ
            count++
        }

        return if (count > 0) Vec3(tx / count, ty / count, tz / count) else null
    }

    /**
     * Resolves [Entity] only for the final filtered list (custom [filter] may need type checks).
     */
    fun forEachMatching(
        origin: Vec3,
        radius: Double,
        filter: (Entity) -> Boolean,
        action: (List<Entity>) -> Unit
    ) {
        idBuffer.clear()
        resultBuffer.clear()

        engine.collectEntityIdsInRange(origin, radius, idBuffer)

        for (i in 0 until idBuffer.size) {
            val id = idBuffer[i]
            val e = engine.level.getEntity(id) ?: continue
            if (filter(e)) {
                resultBuffer.add(e)
            }
        }

        action(resultBuffer)
        resultBuffer.clear()
    }
}
