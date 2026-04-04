package wasabi.paciolimc.concurrent

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Supplier

/**
 * **Project Loom**: virtual-thread offload for blocking I/O (files, sockets, native waits).
 *
 * **Never** mutate [wasabi.paciolimc.engine.PacioliEngine] or Minecraft world state from tasks here—hand results
 * back to the server/render thread.
 */
object PacioliAsync {

    private val io = Executors.newVirtualThreadPerTaskExecutor()

    @JvmStatic
    fun runIo(task: Runnable) {
        io.execute(task)
    }

    @JvmStatic
    fun <T> supplyIoAsync(supplier: Supplier<T>): CompletableFuture<T> =
        CompletableFuture.supplyAsync(supplier, io)
}
