package wasabi.paciolimc

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import wasabi.paciolimc.client.Breadcrumbs

/** Client entry. See `PresentationHooks` for async title/music-style asset prep on virtual threads. */
object PacioliClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register {
            Breadcrumbs.onClientTick()
        }
    }
}
