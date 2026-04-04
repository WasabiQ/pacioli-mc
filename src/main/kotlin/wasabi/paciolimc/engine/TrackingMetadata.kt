package wasabi.paciolimc.engine

/**
 * Cached snapshot for one entity after [PacioliEngine] commit. **Read-only for consumers** after each
 * tick completes; mutation is engine-internal only.
 *
 * Valid on the **server game thread** until the next [PacioliEngine] tick for this level.
 */
class TrackingMetadata {

    var initialized: Boolean = false

    var prevX: Double = 0.0
    var prevY: Double = 0.0
    var prevZ: Double = 0.0
    var currX: Double = 0.0
    var currY: Double = 0.0
    var currZ: Double = 0.0

    /** Squared length of position delta this tick (curr − prev). */
    var deltaLenSq: Double = 0.0

    /** Bitmask: [PacioliEngine.MOVE_*] */
    var movementMask: Int = 0

    /** Living: previous-tick health; non-living: 0. */
    var prevHealth: Float = 0f
    /** Current health after snapshot (LivingEntity only). */
    var health: Float = 0f
    var maxHealth: Float = 0f
    /** [health] − [prevHealth] for this tick (derived once). */
    var healthDelta: Float = 0f

    /**
     * Tier: [HealthTier.NONE] … [HealthTier.NEAR_DEATH].
     * Derived from ratio health/maxHealth; do not recompute in queries.
     */
    var healthTier: Int = HealthTier.NONE

    /** Lower bits: damage/heal flags [PacioliEngine.HEALTH_*]. */
    var healthStateMask: Int = 0

    var spectator: Boolean = false
    var isServerPlayer: Boolean = false

    /** Snapshot of ground contact (LivingEntity). */
    var onGround: Boolean = true

    var lastHorizDirX: Double = 0.0
    var lastHorizDirZ: Double = 0.0
    var hasLastHorizDir: Boolean = false

    /** After first [LivingEntity] sample, enables meaningful health deltas. */
    var hasHealthSnapshot: Boolean = false
}
