package wasabi.paciolimc.proximity

import net.minecraft.entity.Entity
import wasabi.paciolimc.engine.PacioliEngine
import wasabi.paciolimc.logger.PacioliLog
import java.util.HashMap // Optimized for Single-Threaded Server Tick

/**
 * Pacioli Proximity Manager - Alpha 1.4.1
 * Logic: Radial Hysteresis (16m / 20m)
 * Optimization: Single-threaded HashMap & Gated Logging
 */
class ProximityManager(private val engine: PacioliEngine) {

    private val ENTRY_RADIUS = 16.0
    private val EXIT_RADIUS = 20.0 
    private val ENTRY_SQR = ENTRY_RADIUS * ENTRY_RADIUS
    private val EXIT_SQR = EXIT_RADIUS * EXIT_RADIUS

    // DEBUG FLAG: Prevents Disk I/O "Machine Gun" logging
    var debugLogging = false

    /** * Switching to standard HashMap. 
     * Since we are strictly on the Server Thread, ConcurrentHashMap was 
     * paying a "Concurrency Tax" we didn't need to owe.
     */
    private val trackedStates = HashMap<Int, MutableSet<Int>>()
    
    // Internal Buffer: Reused per-observer to kill GC pressure.
    private val candidateBuffer = mutableListOf<Entity>()

    fun update(observer: Entity) {
        // SAFETY CHECK: Ensure we are on the main thread to protect candidateBuffer
        // if (!observer.level().isClientSide && Thread.currentThread() != observer.server?.thread) return

        val obsId = observer.id
        val currentlyTracked = trackedStates.getOrPut(obsId) { HashSet() }
        
        candidateBuffer.clear()
        engine.getEntitiesInRange(observer.pos, EXIT_RADIUS, candidateBuffer)

        val nextTickTracked = HashSet<Int>(currentlyTracked.size + 8)

        for (i in 0 until candidateBuffer.size) {
            val target = candidateBuffer[i]
            if (target.id == obsId) continue

            val distSqr = target.squaredDistanceTo(observer.pos)
            val isAlreadyTracked = currentlyTracked.contains(target.id)

            if (isAlreadyTracked) {
                if (distSqr <= EXIT_SQR) {
                    nextTickTracked.add(target.id)
                }
            } else {
                if (distSqr <= ENTRY_SQR) {
                    nextTickTracked.add(target.id)
                    onEnter(observer, target)
                }
            }
        }

        // 2. CLEANUP: Single Source of Truth for Departures
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
        if (debugLogging) {
            PacioliLog.info("PROXIMITY", "ENTERING: ${target.id} -> ${observer.id}")
        }
    }

    private fun onExit(observer: Entity, targetId: Int) {
        if (debugLogging) {
            PacioliLog.info("PROXIMITY", "EXITING: $targetId -> ${observer.id}")
        }
    }

    fun purgeObserver(observerId: Int) {
        trackedStates.remove(observerId)
    }
}