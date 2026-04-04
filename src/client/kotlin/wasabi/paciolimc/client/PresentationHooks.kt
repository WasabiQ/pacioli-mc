package wasabi.paciolimc.client

import net.minecraft.client.Minecraft
import wasabi.paciolimc.concurrent.PacioliAsync
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Client-side hooks for **title screens**, **music**, and other presentation work.
 * Heavy preparation runs on virtual threads; application to game state must run on the client thread.
 */
object PresentationHooks {

    private val mc: Minecraft get() = Minecraft.getInstance()

    /**
     * Run [loader] on a virtual thread, then [applier] on the next client tick (main thread).
     */
    @JvmStatic
    fun <T> loadAsync(loader: java.util.function.Supplier<T>, applier: Consumer<T>) {
        PacioliAsync.supplyIoAsync(loader).thenAccept { value ->
            mc.execute { applier.accept(value) }
        }
    }

    /** Kotlin-friendly overload. */
    fun <T> loadAsyncKt(loader: () -> T, applier: (T) -> Unit) {
        loadAsync(
            java.util.function.Supplier { loader() },
            Consumer { applier(it) }
        )
    }

    @JvmStatic
    fun loadBytesAsync(pathLabel: String): CompletableFuture<ByteArray> =
        PacioliAsync.supplyIoAsync {
            java.nio.file.Path.of(pathLabel).let { p ->
                if (java.nio.file.Files.isReadable(p)) java.nio.file.Files.readAllBytes(p) else ByteArray(0)
            }
        }
}
