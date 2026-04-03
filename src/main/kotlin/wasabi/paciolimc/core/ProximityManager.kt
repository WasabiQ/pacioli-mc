package wasabi.paciolimc.proximity

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import wasabi.paciolimc.engine.PacioliEngine
import wasabi.paciolimc.logger.PacioliLog
import java.util.HashMap
import java.util.HashSet

/**
 * Pacioli Proximity Manager - Alpha 1.4.7
 * Logic: Radial Hysteresis (Entry: 16m, Exit: 20m)
 */
class ProximityManager(
    private val engine: PacioliEngine,
    private val filter: (Entity) -> Boolean 
) {

    private val ENTRY_RADIUS = 16.0
    private val EXIT_RADIUS = 20.0 
    private val ENTRY_SQR = ENTRY_RADIUS * ENTRY_RADIUS
    private val EXIT_SQR = EXIT_RADIUS * EXIT_RADIUS

    private val trackedStates = HashMap<Int, MutableSet<Int>>()
    private val candidateBuffer = mutableListOf<Entity>()

    fun update(observer: Entity) {
        val obsId = observer.id
        val obsPos = observer.position() // ⚡ Micro-opt: Cache position once
        
        val currentlyTracked = trackedStates.getOrPut(obsId) { HashSet() }
        
        candidateBuffer.clear()
        engine.getEntitiesInRange(obsPos, EXIT_RADIUS, candidateBuffer)

        val nextTickTracked = HashSet<Int>(currentlyTracked.size + 8)

        for (i in 0 until candidateBuffer.size) {
            val target = candidateBuffer[i]
            val targetId = target.id
            if (targetId == obsId) continue

            val distSqr = target.distanceToSqr(obsPos) 
            val isAlreadyTracked = currentlyTracked.contains(targetId)

            if (isAlreadyTracked) {
                // Stay tracked until they cross the OUTER (20m) boundary
                if (distSqr <= EXIT_SQR) {
                    nextTickTracked.add(targetId)
                }
            } else {
                // Only start tracking if they hit the INNER (16m) boundary AND pass filter
                if (distSqr <= ENTRY_SQR && filter(target)) {
                    nextTickTracked.add(targetId)
                    onEnter(observer, target)
                }
            }
        }

        // CLEANUP: Fire exit events for anyone no longer in range
        val iterator = currentlyTracked.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (!nextTickTracked.contains(id)) {
                onExit(observer, id)
                iterator.remove()
            }
        }

        currentlyTracked.addAll(nextTickTracked)
    }

    private fun onEnter(observer: Entity, target: Entity) {
        PacioliLog.info("PROXIMITY", "[${observer.id}] started tracking [${target.id}]")
    }

    private fun onExit(observer: Entity, targetId: Int) {
        PacioliLog.info("PROXIMITY", "[${observer.id}] lost track of [$targetId]")
    }

    fun purgeObserver(observerId: Int) {
        trackedStates.remove(observerId)
    }
}