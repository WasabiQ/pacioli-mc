package wasabi.paciolimc.commands

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.LiteralText
import wasabi.paciolimc.engine.PacioliEngine
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.Vec3

object PacioliCommands {

    lateinit var engine: PacioliEngine
    var breadcrumbsEnabled = false

    fun register(level: ServerLevel) {
        engine = PacioliEngine(level)

        CommandRegistrationCallback.EVENT.register { dispatcher, _ ->

            val pacioliRoot = CommandManager.literal("pacioli")

            // --- /pacioli massspawn <number> <entity> ---
            val massSpawn = CommandManager.literal("massspawn")
                .then(
                    CommandManager.argument("number", IntegerArgumentType.integer(1))
                        .then(
                            CommandManager.argument("entity", StringArgumentType.word())
                                .executes { ctx ->
                                    val source = ctx.source
                                    if (!isDevServer(source)) {
                                        source.sendFeedback(LiteralText("This command is disabled on public servers."), false)
                                        return@executes 0
                                    }

                                    val number = IntegerArgumentType.getInteger(ctx, "number")
                                    val entityName = StringArgumentType.getString(ctx, "entity")
                                    spawnMass(source, number, entityName)
                                    1
                                }
                        )
                )

            // --- /pacioli enable breadcrumbs ---
            val enableBreadcrumbs = CommandManager.literal("enable")
                .then(
                    CommandManager.literal("breadcrumbs")
                        .executes {
                            val source = it.source
                            if (!isDevServer(source)) {
                                source.sendFeedback(LiteralText("Disabled on public servers."), false)
                                return@executes 0
                            }

                            breadcrumbsEnabled = true
                            source.sendFeedback(LiteralText("Breadcrumb tracking enabled."), false)
                            1
                        }
                )

            // --- /pacioli disable breadcrumbs ---
            val disableBreadcrumbs = CommandManager.literal("disable")
                .then(
                    CommandManager.literal("breadcrumbs")
                        .executes {
                            val source = it.source
                            if (!isDevServer(source)) {
                                source.sendFeedback(LiteralText("Disabled on public servers."), false)
                                return@executes 0
                            }

                            breadcrumbsEnabled = false
                            source.sendFeedback(LiteralText("Breadcrumb tracking disabled."), false)
                            1
                        }
                )

            // --- /pacioli clear breadcrumbs ---
            val clearBreadcrumbs = CommandManager.literal("clear")
                .then(
                    CommandManager.literal("breadcrumbs")
                        .executes {
                            val source = it.source
                            if (!isDevServer(source)) {
                                source.sendFeedback(LiteralText("Disabled on public servers."), false)
                                return@executes 0
                            }

                            engine.clearBreadcrumbs()
                            source.sendFeedback(LiteralText("Breadcrumb history cleared."), false)
                            1
                        }
                )

            dispatcher.register(
                pacioliRoot.then(massSpawn)
                    .then(enableBreadcrumbs)
                    .then(disableBreadcrumbs)
                    .then(clearBreadcrumbs)
            )
        }
    }

    // --- DEV CHECK: only allow singleplayer/LAN ---
    private fun isDevServer(source: ServerCommandSource): Boolean {
        val server = source.server
        return !server.isDedicated
    }

    // --- SPAWN FUNCTION ---
    private fun spawnMass(source: ServerCommandSource, number: Int, entityName: String) {
        val level = engine.world
        val pos = source.player?.position() ?: Vec3(0.0, 80.0, 0.0)

        val type = EntityType.byKey(entityName.lowercase())
        if (type == null) {
            source.sendFeedback(LiteralText("Unknown entity type: $entityName"), false)
            return
        }

        repeat(number) {
            val spawnPos = pos.add((Math.random() - 0.5) * 20, 0.0, (Math.random() - 0.5) * 20)
            val entity = type.create(level)
            if (entity is LivingEntity) {
                entity.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, 0f, 0f)
                level.addFreshEntity(entity)
            }
        }

        source.sendFeedback(LiteralText("Spawned $number $entityName"), false)
    }
}