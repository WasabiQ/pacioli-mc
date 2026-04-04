package wasabi.paciolimc.engine

/** Movement classification — derived once per tick from position delta (+ ground). */
object MovementBits {
    const val STATIONARY: Int = 1 shl 0
    const val MOVING: Int = 1 shl 1
    const val FAST: Int = 1 shl 2
    const val AIRBORNE: Int = 1 shl 3
    const val FALLING: Int = 1 shl 4
    const val DIR_CHANGE: Int = 1 shl 5
}

/** Health delta flags (lower bits of [TrackingMetadata.healthStateMask]). */
object HealthBits {
    const val DAMAGE: Int = 1 shl 0
    const val HEAL: Int = 1 shl 1
}

/** Exclusive tier in [TrackingMetadata.healthTier]. */
object HealthTier {
    const val NONE: Int = 0
    const val HEALTHY: Int = 1
    const val INJURED: Int = 2
    const val CRITICAL: Int = 3
    const val NEAR_DEATH: Int = 4
}
