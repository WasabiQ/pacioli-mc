package wasabi.paciolimc.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.*
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.entity.Entity
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import wasabi.paciolimc.engine.PacioliEngine
import wasabi.paciolimc.logger.PacioliLog
import java.util.ArrayList
import java.util.concurrent.ThreadLocalRandom

/**
 * Pacioli Control Suite - Alpha 1.3 (Final Hardening)
 * Optimized for high-frequency testing and explicit diagnostics.
 */
object PacioliCommands {

    private var engine: PacioliEngine? = null
    
    @JvmStatic
    var breadcrumbsEnabled = false
        private set

    // Thread-safe: Commands execute on the Server Thread. 
    private val queryResults = ArrayList<Entity>(512)

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            register(dispatcher)
        }
    }

    private fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("pacioli")
                .requires { isDev(it) } 
                .then(literal("status").executes { checkStatus(it.source) })
                .then(literal("sweep").executes { triggerManualSweep(it.source) })
                
                .then(literal("query")
                    .then(argument("radius", IntegerArgumentType.integer(1, 100))
                        .executes { ctx ->
                            testQuery(ctx.source, IntegerArgumentType.getInteger(ctx, "radius").toDouble())
                        }
                    )
                )

                .then(literal("massspawn")
                    .then(argument("number", IntegerArgumentType.integer(1, 500))
                        .then(argument("entity", StringArgumentType.word())
                            .executes { ctx ->
                                spawnMass(
                                    ctx.source, 
                                    IntegerArgumentType.getInteger(ctx, "number"), 
                                    StringArgumentType.getString(ctx, "entity")
                                )
                                1
                            }
                        )
                    )
                )

                .then(literal("breadcrumbs")
                    .then(literal("enable").executes { toggleBreadcrumbs(it.source, true) })
                    .then(literal("disable").executes { toggleBreadcrumbs(it.source, false) })
                    .then(literal("clear").executes { clearCache(it.source) })
                )
        )
    }

    fun setEngine(pacioliEngine: PacioliEngine) {
        this.engine = pacioliEngine
    }

    private fun isDev(source: ServerCommandSource): Boolean {
        return !source.server.isDedicated && source.hasPermissionLevel(2)
    }

    private fun checkStatus(source: ServerCommandSource): Int {
        val color = if (engine != null) "§aActive" else "§cInactive"
        val bcStatus = if (breadcrumbsEnabled) "§aEnabled" else "§7Disabled"
        source.sendFeedback({ Text.literal("§7[Pacioli] Engine: $color §7| Breadcrumbs: $bcStatus") }, false)
        return 1
    }

    private fun triggerManualSweep(source: ServerCommandSource): Int {
        val eng = engine ?: return errNoEngine(source)
        val startTime = System.nanoTime()
        eng.safetySweep()
        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
        
        val rounded = (durationMs * 1000.0).toInt() / 1000.0
        source.sendFeedback({ Text.literal("§7[Pacioli] Safety sweep: §f${rounded}ms") }, false)
        return 1
    }

    private fun testQuery(source: ServerCommandSource, radius: Double): Int {
        val eng = engine ?: return errNoEngine(source)
        val pos = source.position
        
        val startTime = System.nanoTime()
        eng.getEntitiesInRange(pos, radius, queryResults)
        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
        
        val rounded = (durationMs * 1000.0).toInt() / 1000.0
        source.sendFeedback({ Text.literal("§7[Pacioli] Found §f${queryResults.size} §7entities in §f${rounded}ms") }, false)
        
        PacioliLog.metric("COMMAND_QUERY", durationMs, queryResults.size)
        return 1
    }

    private fun toggleBreadcrumbs(source: ServerCommandSource, enabled: Boolean): Int {
        breadcrumbsEnabled = enabled
        PacioliLog.info("BREADCRUMBS", "Diagnostic trails set to: $enabled")
        source.sendFeedback({ Text.literal("§7[Pacioli] Breadcrumbs ${if (enabled) "§aEnabled" else "§cDisabled"}") }, false)
        return 1
    }

    private fun clearCache(source: ServerCommandSource): Int {
        val eng = engine ?: return errNoEngine(source)
        eng.clearCache()
        source.sendFeedback({ Text.literal("§7[Pacioli] Spatial cache hard reset successful.") }, false)
        return 1
    }

    private fun spawnMass(source: ServerCommandSource, number: Int, entityName: String) {
        val world = source.world
        val pos = source.position
        val random = ThreadLocalRandom.current()
        
        val id = Identifier.tryParse(entityName.lowercase()) ?: run {
            source.sendError(Text.literal("§c[Pacioli] Invalid Identifier: $entityName"))
            return
        }

        val type = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null) ?: run {
            source.sendError(Text.literal("§c[Pacioli] Entity '$id' not found."))
            return
        }

        repeat(number) {
            val angle = random.nextDouble() * 2 * Math.PI
            val dist = random.nextDouble() * 8.0
            val spawnPos = Vec3d(pos.x + Math.cos(angle) * dist, pos.y + 1.0, pos.z + Math.sin(angle) * dist)

            type.create(world)?.let { entity ->
                entity.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, 0f, 0f)
                entity.velocity = Vec3d((random.nextDouble() - 0.5) * 1.5, 0.3, (random.nextDouble() - 0.5) * 1.5)
                world.spawnEntity(entity)
            }
        }
        PacioliLog.info("COMMAND", "SpawnMass: $number x $id")
        source.sendFeedback({ Text.literal("§7[Pacioli] Summoned §f$number §7of §f$id") }, false)
    }

    private fun errNoEngine(source: ServerCommandSource): Int {
        source.sendError(Text.literal("§c[Pacioli] Engine not initialized. Check server logs."))
        return 0
    }
}