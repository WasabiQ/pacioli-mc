package wasabi.paciolimc.proximity

import net.minecraft.world.entity.Entity
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import wasabi.paciolimc.engine.PacioliEngine
import java.util.ArrayList

/**
 * Pacioli Tactical Scanner - Alpha 1.4.7
 * High-performance spatial queries with Zero-Allocation buffers.
 */
class TacticalScanner(private val engine: PacioliEngine) {

    private val rawBuffer = mutableListOf<Entity>()
    private val resultBuffer = ArrayList<Entity>()

    /**
     * Calculates the "Center of Mass" for all active players in a radius.
     * Use for: Centering boss arenas or group-scaled difficulty.
     */
    fun getPlayerCentroid(origin: Vec3, radius: Double): Vec3? {
        rawBuffer.clear()
        engine.getEntitiesInRange(origin, radius, rawBuffer)
        
        var tx = 0.0; var ty = 0.0; var tz = 0.0
        var count = 0

        for (i in 0 until rawBuffer.size) {
            val e = rawBuffer[i]
            if (e is ServerPlayer && !e.isSpectator) {
                tx += e.x; ty += e.y; tz += e.z
                count++
            }
        }

        return if (count > 0) Vec3(tx / count, ty / count, tz / count) else null
    }

    /**
     * Identifies entities matching a filter and passes them to a safe scope.
     * 🛡️ SAFETY: Result list is only valid WITHIN the [action] block.
     */
    fun forEachMatching(origin: Vec3, radius: Double, filter: (Entity) -> Boolean, action: (List<Entity>) -> Unit) {
        rawBuffer.clear()
        resultBuffer.clear()
        
        engine.getEntitiesInRange(origin, radius, rawBuffer)

        for (i in 0 until rawBuffer.size) {
            val e = rawBuffer[i]
            if (filter(e)) {
                resultBuffer.add(e)
            }
        }

        action(resultBuffer)
        resultBuffer.clear() // Defensive wipe
    }
}