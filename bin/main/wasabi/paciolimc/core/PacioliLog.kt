package wasabi.paciolimc.logger

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import net.minecraft.util.math.Vec3d

/**
 * Pacioli Alpha 1.3 - High-Performance Telemetry
 * Optimized for zero-allocation, fast truncation, and SLF4J safety.
 */
object PacioliLog {
    private val LOGGER: Logger = LoggerFactory.getLogger("Pacioli")
    private const val PREFIX = "[Pacioli]"
    
    var ENABLED = true

    val METRIC: Marker = MarkerFactory.getMarker("PACIOLI_METRIC")
    val STATE: Marker = MarkerFactory.getMarker("PACIOLI_STATE")
    val TRACE: Marker = MarkerFactory.getMarker("PACIOLI_TRACE")

    // FAST TRUNCATION: Log-use only. Do NOT use for physics/logic.
    private fun f2(v: Double): Double = (v * 100.0).toInt() / 100.0
    private fun f3(v: Double): Double = (v * 1000.0).toInt() / 1000.0

    fun trace(context: String, msg: String) {
        if (ENABLED && LOGGER.isTraceEnabled) {
            LOGGER.trace(TRACE, "{} [{}] {}", PREFIX, context, msg)
        }
    }

    fun pos(context: String, id: Int, pos: Vec3d) {
        if (ENABLED && LOGGER.isInfoEnabled) {
            LOGGER.info(STATE, "{} [{}] ID:{} @ [{}, {}, {}]", 
                PREFIX, context, id, f2(pos.x), f2(pos.y), f2(pos.z))
        }
    }

    fun stateChange(context: String, id: Int, from: String, to: String) {
        if (ENABLED && LOGGER.isInfoEnabled) {
            LOGGER.info(STATE, "{} [{}] ID:{} {} -> {}", PREFIX, context, id, from, to)
        }
    }

    fun metric(context: String, durationMs: Double, count: Int) {
        if (!ENABLED) return
        when {
            durationMs > 5.0 -> 
                LOGGER.error(METRIC, "{} [{}] CRITICAL {} units in {}ms", PREFIX, context, count, f3(durationMs))
            durationMs > 1.0 -> 
                LOGGER.warn(METRIC, "{} [{}] SLOW {} units in {}ms", PREFIX, context, count, f3(durationMs))
            LOGGER.isDebugEnabled -> 
                LOGGER.debug(METRIC, "{} [{}] {} units in {}ms", PREFIX, context, count, f3(durationMs))
        }
    }

    // Quality-of-Life: Contextual overloads for consistency
    fun info(context: String, msg: String) {
        if (ENABLED) LOGGER.info("{} [{}] {}", PREFIX, context, msg)
    }

    fun warn(context: String, msg: String) {
        if (ENABLED) LOGGER.warn("{} [{}] {}", PREFIX, context, msg)
    }

    fun error(context: String, msg: String, t: Throwable? = null) {
    if (!ENABLED) return
    if (t != null) {
        LOGGER.error("{} [{}] {}", PREFIX, context, msg, t)
    } else {
        LOGGER.error("{} [{}] {}", PREFIX, context, msg)
    }
}