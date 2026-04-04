package wasabi.paciolimc

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import org.slf4j.LoggerFactory
import wasabi.paciolimc.commands.PacioliCommands
import wasabi.paciolimc.engine.PacioliEngine

object Pacioli : ModInitializer {
    private val logger = LoggerFactory.getLogger("pacioli")

    override fun onInitialize() {
        logger.info("Pacioli initialized")

        PacioliCommands.init()

        ServerTickEvents.END_LEVEL_TICK.register { level ->
            PacioliEngine.tick(level)
        }

        ServerLevelEvents.UNLOAD.register { _, level ->
            PacioliEngine.removeLevel(level)
        }
    }
}
