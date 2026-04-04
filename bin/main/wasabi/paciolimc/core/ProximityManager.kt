package wasabi.paciolimc.proximity

import net.minecraft.world.entity.Entity
import wasabi.paciolimc.engine.PacioliEngine
import java.util.HashSet

/**
 * Pacioli Proximity Manager - Alpha 1.5.5
 * milestone: Hardened Core
 * Logic: Staggered Evaluation & Interface Decoupling
 */
class ProximityManager(
    private val engine: PacioliEngine,
    private val filter: (Entity) -> Boolean,
    private val listener: TrackingListener? = null 
) {
    private val ENTRY_SQR = 256.0 
    private val EXIT_SQR = 400.0  

    private val trackedStates = HashMap<Int, MutableSet<Int>>()
    private val candidateBuffer = mutableListOf<Entity>()
    private val nextTickBuffer = HashSet<Int>()

    fun update(observer: Entity) {
        val obsId = observer.id
        val obsPos = observer.position()
        val currentlyTracked = trackedStates.getOrPut(obsId) { HashSet() }
        
        candidateBuffer.clear()
        engine.getCandidateBufferBroadPhase(obsPos, candidateBuffer)

        nextTickBuffer.clear()

        for (i in 0 until candidateBuffer.size) {
            val target = candidateBuffer[i]
            val targetId = target.id
            if (targetId == obsId) continue

            val isAlreadyTracked = currentlyTracked.contains(targetId)
            val distSqr = target.distanceToSqr(obsPos)

            if (isAlreadyTracked) {
                if (distSqr <= EXIT_SQR) {
                    nextTickBuffer.add(targetId)
                    
                    // Staggered load balancing to prevent CPU spikes
                    if ((targetId + obsId) % 10 == 0) {
                        listener?.onVitals(target)
                    }
                }
            } else if (distSqr <= ENTRY_SQR && filter(target)) {
                nextTickBuffer.add(targetId)
                listener?.onEnter(observer, target)
            }
        }
        
        // Reconciliation
        val iterator = currentlyTracked.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (!nextTickBuffer.contains(id)) {
                listener?.onExit(observer, id)
                iterator.remove() 
            }
        }
        
        currentlyTracked.addAll(nextTickBuffer)
    }

    fun purgeObserver(observerId: Int) = trackedStates.remove(observerId)
}

/**
 * Decoupling Interface for external logic
 */
interface TrackingListener {
    fun onEnter(observer: Entity, target: Entity)
    fun onExit(observer: Entity, targetId: Int)
    fun onVitals(target: Entity)
}