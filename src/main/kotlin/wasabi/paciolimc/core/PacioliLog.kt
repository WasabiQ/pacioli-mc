package wasabi.paciolimc.logger

import net.minecraft.world.phys.Vec3
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Structured logging for Pacioli. Prefer [structured] for grep-friendly fields.
 * Optional async path uses **virtual threads** (JDK 21+) so disk I/O does not hitch the server tick.
 */
object PacioliLog {
    private val LOGGER: Logger = LoggerFactory.getLogger("Pacioli")
    private const val PREFIX = "[Pacioli]"
    private const val ASYNC_QUEUE_CAP = 512

    @JvmField
    var ENABLED: Boolean = true

    /** When true, [info]/[warn]/[error] enqueue to a bounded queue consumed on a virtual thread. */
    @JvmField
    var ASYNC_ENABLED: Boolean = false

    private val rateLimitUntilNanos = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val rateLimitLock = Any()

    private val asyncExecutor = lazy {
        Executors.newVirtualThreadPerTaskExecutor()
    }

    private data class AsyncEntry(val level: Int, val line: String, val t: Throwable?)

    private val asyncQueue = ArrayBlockingQueue<AsyncEntry>(ASYNC_QUEUE_CAP)

    init {
        Thread.startVirtualThread {
            while (true) {
                try {
                    val e = asyncQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    when (e.level) {
                        0 -> LOGGER.info(e.line)
                        1 -> LOGGER.warn(e.line)
                        2 -> if (e.t != null) LOGGER.error(e.line, e.t) else LOGGER.error(e.line)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun offerAsync(level: Int, line: String, t: Throwable? = null) {
        if (!asyncQueue.offer(AsyncEntry(level, line, t))) {
            LOGGER.warn("{} [LOG] async queue full; dropped message", PREFIX)
        }
    }

    val METRIC: Marker = MarkerFactory.getMarker("PACIOLI_METRIC")
    val STATE: Marker = MarkerFactory.getMarker("PACIOLI_STATE")
    val TRACE: Marker = MarkerFactory.getMarker("PACIOLI_TRACE")

    private fun f2(v: Double): Double = (v * 100.0).toInt() / 100.0
    private fun f3(v: Double): Double = (v * 1000.0).toInt() / 1000.0

    fun structured(level: org.slf4j.event.Level, subsystem: String, msg: String, fields: Map<String, Any?> = emptyMap()) {
        if (!ENABLED) return
        val suffix = if (fields.isEmpty()) "" else " " + fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val line = "$PREFIX [$subsystem] $msg$suffix"
        when (level) {
            org.slf4j.event.Level.TRACE -> if (LOGGER.isTraceEnabled) LOGGER.trace(line)
            org.slf4j.event.Level.DEBUG -> if (LOGGER.isDebugEnabled) LOGGER.debug(line)
            org.slf4j.event.Level.INFO -> LOGGER.info(line)
            org.slf4j.event.Level.WARN -> LOGGER.warn(line)
            org.slf4j.event.Level.ERROR -> LOGGER.error(line)
        }
    }

    fun trace(context: String, msg: String) {
        if (ENABLED && LOGGER.isTraceEnabled) {
            LOGGER.trace(TRACE, "{} [{}] {}", PREFIX, context, msg)
        }
    }

    fun pos(context: String, id: Int, pos: Vec3) {
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

    fun info(context: String, msg: String) {
        if (!ENABLED) return
        val line = "$PREFIX [$context] $msg"
        if (ASYNC_ENABLED) offerAsync(0, line) else LOGGER.info(line)
    }

    fun warn(context: String, msg: String) {
        if (!ENABLED) return
        val line = "$PREFIX [$context] $msg"
        if (ASYNC_ENABLED) offerAsync(1, line) else LOGGER.warn(line)
    }

    /** At most one log per [key] per [minIntervalMs] (wall clock). */
    fun warnRateLimited(context: String, key: String, msg: String, minIntervalMs: Long = 5000L) {
        if (!ENABLED) return
        val now = System.nanoTime()
        val next = now + TimeUnit.MILLISECONDS.toNanos(minIntervalMs)
        synchronized(rateLimitLock) {
            val prev = rateLimitUntilNanos[key] ?: 0L
            if (now < prev) return
            rateLimitUntilNanos[key] = next
        }
        warn(context, msg)
    }

    fun error(context: String, msg: String, t: Throwable? = null) {
        if (!ENABLED) return
        val line = "$PREFIX [$context] $msg"
        if (ASYNC_ENABLED) offerAsync(2, line, t) else {
            if (t != null) LOGGER.error(line, t) else LOGGER.error(line)
        }
    }

    /**
     * Run [block] on a **virtual thread** for blocking I/O. Never touch game state here.
     */
    fun runIoAsync(block: Runnable) {
        asyncExecutor.value.execute(block)
    }
}
