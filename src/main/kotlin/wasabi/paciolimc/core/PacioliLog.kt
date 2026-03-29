package wasabi.paciolimc.logger

import org.slf4j.LoggerFactory

object PacioliLog {
    private val logger = LoggerFactory.getLogger("Pacioli Log")

    fun info(msg: String) = logger.info(msg)
    fun debug(msg: String) = logger.debug(msg)
    fun warn(msg: String) = logger.warn(msg)
    fun error(msg: String) = logger.error(msg)
}