package wasabi.paciolimc.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.server.command.CommandManager.*
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.entity.Entity
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import wasabi.paciolimc.engine.PacioliEngine
import java.util.ArrayList

/**
 * Pacioli Control Suite - Alpha 1.2
 * Handles engine diagnostics, mass-stress testing, and diagnostic toggles.
 */
object PacioliCommands {

    private var engine: PacioliEngine? = null
    
    @JvmStatic
    var breadcrumbsEnabled = false
        private set

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            register(dispatcher)
        }
    }

    private fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("pacioli")
                .requires { it.hasPermissionLevel(2) }
                // Diagnostic Tools
                .then(literal("status").executes { if (isDev(it.source)) checkStatus(it.source) else 0 })
                .then(literal("sweep").executes { if (isDev(it.source)) triggerManualSweep(it.source) else 0 })
                
                // Spatial Query Testing
                .then(literal("query")
                    .then(argument("radius", IntegerArgumentType.integer(1, 100))
                        .executes { ctx ->
                            if (!isDev(ctx.source)) return@executes 0
                            val radius = IntegerArgumentType.getInteger(ctx, "radius").toDouble()
                            testQuery(ctx.source, radius)
                        }
                    )
                )

                // AI Stress Testing
                .then(literal("massspawn")
                    .then(argument("number", IntegerArgumentType.integer(1, 500))
                        .then(argument("entity", StringArgumentType.word())
                            .executes { ctx ->
                                if (!isDev(ctx.source)) return@executes 0
                                val count = IntegerArgumentType.getInteger(ctx, "number")
                                val name = StringArgumentType.getString(ctx, "entity")
                                spawnMass(ctx.source, count, name)
                                1
                            }
                        )
                    )
                )

                // Breadcrumbs Module Control
                .then(literal("breadcrumbs")
                    .then(literal("enable").executes { if (isDev(it.source)) toggleBreadcrumbs(it.source, true) else 0 })
                    .then(literal("disable").executes { if (isDev(it.source)) toggleBreadcrumbs(it.source, false) else 0 })
                    .then(literal("clear").executes { if (isDev(it.source)) clearCache(it.source) else 0 })
                )
        )
    }

    fun setEngine(pacioliEngine: PacioliEngine) {
        this.engine = pacioliEngine
    }

    /**
     * Security: Restricts dangerous engine commands to Singleplayer/Local Integrated servers.
     */
    private fun isDev(source: ServerCommandSource): Boolean {
        val isDedicated = source.server.isDedicated
        if (isDedicated) {
            source.sendError(Text.literal("§c[Pacioli] Access Denied: Dev tools restricted to local environments."))
            return false
        }
        return true
    }

    private fun checkStatus(source: ServerCommandSource): Int {
        val active = engine != null
        val color = if (active) "§aActive" else "§cInactive"
        val bcStatus = if (breadcrumbsEnabled) "§aEnabled" else "§7Disabled"
        
        source.sendFeedback({ Text.literal("§7[Pacioli] Engine: $color §7| Breadcrumbs: $bcStatus") }, false)
        return 1
    }

    private fun triggerManualSweep(source: ServerCommandSource): Int {
        val eng = engine ?: return 0
        val startTime = System.nanoTime()
        eng.safetySweep()
        val duration = (System.nanoTime() - startTime) / 1_000_000.0
        
        source.sendFeedback({ Text.literal("§7[Pacioli] Safety sweep completed in §f${"%.3f".format(duration)}ms") }, false)
        return 1
    }

    private fun testQuery(source: ServerCommandSource, radius: Double): Int {
        val eng = engine ?: return 0
        val results = ArrayList<Entity>()
        val pos = source.position
        
        val startTime = System.nanoTime()
        eng.getEntitiesInRange(pos, radius, results)
        val duration = (System.nanoTime() - startTime) / 1_000_000.0

        source.sendFeedback({ Text.literal("§7[Pacioli] Query: Found §f${results.size} §7entities in §f${"%.3f".format(duration)}ms") }, false)
        return 1
    }

    private fun toggleBreadcrumbs(source: ServerCommandSource, enabled: Boolean): Int {
        breadcrumbsEnabled = enabled
        val status = if (enabled) "§aEnabled" else "§cDisabled"
        source.sendFeedback({ Text.literal("§7[Pacioli] Breadcrumbs $status") }, false)
        return 1
    }

    private fun clearCache(source: ServerCommandSource): Int {
        engine?.clearCache() ?: return 0
        source.sendFeedback({ Text.literal("§7[Pacioli] Spatial cache hard reset successful.") }, false)
        return 1
    }

    private fun spawnMass(source: ServerCommandSource, number: Int, entityName: String) {
        val world = source.world
        val pos = source.position
        
        val id = Identifier.tryParse(entityName.lowercase()) ?: run {
            source.sendError(Text.literal("§c[Pacioli] Invalid entity name: $entityName"))
            return
        }
        
        val type = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null) ?: run {
            source.sendError(Text.literal("§c[Pacioli] Entity '$entityName' not found in registry."))
            return
        }

        repeat(number) {
            val angle = Math.random() * 2 * Math.PI
            val dist = Math.random() * 8.0
            val spawnPos = Vec3d(pos.x + Math.cos(angle) * dist, pos.y + 1.0, pos.z + Math.sin(angle) * dist)

            type.create(world)?.let { entity ->
                entity.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, 0f, 0f)
                // Random velocity "explosion" to test spatial update frequency
                entity.velocity = Vec3d((Math.random() - 0.5) * 1.5, 0.3, (Math.random() - 0.5) * 1.5)
                world.spawnEntity(entity)
            }
        }
        source.sendFeedback({ Text.literal("§7[Pacioli] Successfully summoned §f$number §7entities for testing.") }, false)
    }
}