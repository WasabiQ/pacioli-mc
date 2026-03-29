package wasabi.paciolimc

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import wasabi.paciolimc.core.PacioliEngine

object Pacioli : ModInitializer {
    private val logger = LoggerFactory.getLogger("pacioli")

    override fun onInitialize() {
        logger.info("Pacioli initialized")

        ServerTickEvents.END_WORLD_TICK.register { world: ServerLevel ->
            PacioliEngine.tick(world)
        }
    }
}