package wasabi.paciolimc.proximity

import net.minecraft.world.entity.Entity
import wasabi.paciolimc.engine.PacioliEngine
import java.util.ArrayList
import java.util.HashSet

/**
 * Staggered proximity: broad phase via cell cube ([PacioliEngine.collectCandidateEntityIds]),
 * narrow phase distances via [PacioliEngine.distanceSqBetweenTracked] (cached positions only).
 */
class ProximityManager(
    private val engine: PacioliEngine,
    private val filter: (Entity) -> Boolean,
    private val listener: TrackingListener? = null
) {
    private val entrySqr = 256.0
    private val exitSqr = 400.0

    private val trackedStates = HashMap<Int, MutableSet<Int>>()
    private val candidateIds = ArrayList<Int>(256)
    private val nextTickBuffer = HashSet<Int>()

    fun update(observer: Entity) {
        val obsId = observer.id
        val obsPos = engine.getObserverPosition(observer)
        val currentlyTracked = trackedStates.getOrPut(obsId) { HashSet() }

        candidateIds.clear()
        engine.collectCandidateEntityIds(obsPos, 24.0, candidateIds)

        nextTickBuffer.clear()

        for (i in 0 until candidateIds.size) {
            val targetId = candidateIds[i]
            if (targetId == obsId) continue

            val distSqr = engine.distanceSqBetweenTracked(obsId, targetId) ?: continue

            val isAlreadyTracked = currentlyTracked.contains(targetId)
            val target = engine.level.getEntity(targetId) ?: continue

            if (isAlreadyTracked) {
                if (distSqr <= exitSqr) {
                    nextTickBuffer.add(targetId)
                    if ((targetId + obsId) % 10 == 0) {
                        listener?.onVitals(target)
                    }
                }
            } else if (distSqr <= entrySqr && filter(target)) {
                nextTickBuffer.add(targetId)
                listener?.onEnter(observer, target)
            }
        }

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

interface TrackingListener {
    fun onEnter(observer: Entity, target: Entity)
    fun onExit(observer: Entity, targetId: Int)
    fun onVitals(target: Entity)
}
