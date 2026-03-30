package wasabi.paciolimc.commands

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.*
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import wasabi.paciolimc.engine.PacioliEngine
import wasabi.paciolimc.logger.PacioliLog
import net.minecraft.entity.Entity
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.world.phys.Vec3
import java.util.ArrayList

object PacioliCommands {

    private var engine: PacioliEngine? = null
    var breadcrumbsEnabled = false

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("pacioli")
                    .requires { it.hasPermissionLevel(2) }
                    // All commands now follow the same Dev Environment restriction for consistency
                    .then(literal("status").executes { if (isDev(it.source)) checkStatus(it.source) else 0 })
                    .then(literal("sweep").executes { if (isDev(it.source)) triggerManualSweep(it.source) else 0 })
                    .then(literal("query")
                        .then(argument("radius", IntegerArgumentType.integer(1, 100))
                            .executes { ctx ->
                                if (!isDev(ctx.source)) return@executes 0
                                val radius = IntegerArgumentType.getInteger(ctx, "radius")
                                testQuery(ctx.source, radius.toDouble())
                                1
                            }
                        )
                    )
                    .then(literal("massspawn")
                        .then(argument("number", IntegerArgumentType.integer(1))
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
                    .then(literal("breadcrumbs")
                        .then(literal("enable").executes { if (isDev(it.source)) toggleBreadcrumbs(it.source, true) else 0 })
                        .then(literal("disable").executes { if (isDev(it.source)) toggleBreadcrumbs(it.source, false) else 0 })
                        .then(literal("clear").executes { if (isDev(it.source)) clearCache(it.source) else 0 })
                    )
            )
        }
    }

    fun setEngine(pacioliEngine: PacioliEngine) {
        this.engine = pacioliEngine
    }

    private fun isDev(source: ServerCommandSource): Boolean {
        val isDedicated = source.server.isDedicated
        if (isDedicated) {
            source.sendError(Text.literal("[Pacioli] Error: Dev tools are restricted to local environments."))
        }
        return !isDedicated
    }

    private fun checkStatus(source: ServerCommandSource): Int {
        val eng = engine
        if (eng == null) {
            source.sendError(Text.literal("[Pacioli] Error: Engine instance is null."))
            return 0
        }
        source.sendFeedback({ Text.literal("[Pacioli] Engine: Active | Breadcrumbs: $breadcrumbsEnabled") }, false)
        return 1
    }

    private fun triggerManualSweep(source: ServerCommandSource): Int {
        val eng = engine ?: return 0
        val startTime = System.nanoTime()
        eng.safetySweep()
        val duration = (System.nanoTime() - startTime) / 1_000_000.0
        
        source.sendFeedback({ Text.literal("[Pacioli] Sweep: Completed in ${String.format("%.3f", duration)}ms") }, false)
        return 1
    }

    private fun testQuery(source: ServerCommandSource, radius: Double): Int {
        val eng = engine
        if (eng == null) {
            source.sendError(Text.literal("[Pacioli] Error: Engine not initialized."))
            return 0
        }
        
        val results = ArrayList<Entity>()
        val startTime = System.nanoTime()
        eng.getEntitiesInRange(source.position, radius, results)
        val duration = (System.nanoTime() - startTime) / 1_000_000.0

        source.sendFeedback({ Text.literal("[Pacioli] Query: Found ${results.size} entities in ${String.format("%.3f", duration)}ms") }, false)
        return 1
    }

    private fun toggleBreadcrumbs(source: ServerCommandSource, enabled: Boolean): Int {
        breadcrumbsEnabled = enabled
        source.sendFeedback({ Text.literal("[Pacioli] Breadcrumbs set to $enabled") }, false)
        return 1
    }

    private fun clearCache(source: ServerCommandSource): Int {
        engine?.clearCache() ?: return 0
        source.sendFeedback({ Text.literal("[Pacioli] Cache: Hard reset successful.") }, false)
        return 1
    }

    private fun spawnMass(source: ServerCommandSource, number: Int, entityName: String) {
        val world = source.world
        val pos = source.position
        
        val id = Identifier.tryParse(entityName.lowercase())
        if (id == null) {
            source.sendError(Text.literal("[Pacioli] Error: Invalid identifier format for '$entityName'"))
            return
        }
        
        val type = Registries.ENTITY_TYPE.getOrEmpty(id).orElse(null)
        if (type == null) {
            source.sendError(Text.literal("[Pacioli] Error: Entity type '$entityName' not found in registry."))
            return
        }

        val capped = number.coerceAtMost(500)
        repeat(capped) {
            val angle = Math.random() * 2 * Math.PI
            val radius = Math.random() * 8.0
            val spawnPos = pos.add(Math.cos(angle) * radius, 1.0, Math.sin(angle) * radius)

            type.create(world)?.let { entity ->
                entity.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, 0f, 0f)
                entity.deltaMovement = Vec3((Math.random() - 0.5) * 1.5, 0.3, (Math.random() - 0.5) * 1.5)
                world.spawnEntity(entity)
            }
        }
        source.sendFeedback({ Text.literal("[Pacioli] Spawn: Successfully summoned $capped entities.") }, false)
    }
}