package wasabi.paciolimc.engine

/**
 * Immutable copy of [TrackingMetadata] for safe handoff to other mods after tick.
 */
data class EntityTrackingSnapshot(
    val tickSerial: Long,
    val prevX: Double,
    val prevY: Double,
    val prevZ: Double,
    val currX: Double,
    val currY: Double,
    val currZ: Double,
    val deltaLenSq: Double,
    val movementMask: Int,
    val prevHealth: Float,
    val health: Float,
    val maxHealth: Float,
    val healthDelta: Float,
    val healthTier: Int,
    val healthStateMask: Int,
    val spectator: Boolean,
    val isServerPlayer: Boolean,
    val onGround: Boolean,
)
