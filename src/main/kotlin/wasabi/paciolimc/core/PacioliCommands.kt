package wasabi.paciolimc.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.permissions.PermissionSetSupplier
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.Commands
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.phys.Vec3
import wasabi.paciolimc.api.PacioliAPI
import wasabi.paciolimc.engine.HealthBits
import wasabi.paciolimc.engine.HealthTier
import wasabi.paciolimc.engine.MovementBits
import wasabi.paciolimc.engine.PacioliEngine
import wasabi.paciolimc.logger.PacioliLog
import java.util.ArrayList
import java.util.concurrent.ThreadLocalRandom

/**
 * Debug and diagnostic commands (Minecraft **26.1** / [CommandSourceStack]).
 */
object PacioliCommands {

    @JvmStatic
    var breadcrumbsEnabled: Boolean = false
        private set

    private val queryResults = ArrayList<Entity>(512)
    private val idQueryResults = ArrayList<Int>(2048)

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            register(dispatcher)
        }
    }

    private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("pacioli")
                .requires { src: CommandSourceStack ->
                    !src.server.isDedicatedServer &&
                        Commands.hasPermission(Commands.LEVEL_MODERATORS).test(src as PermissionSetSupplier)
                }
                .then(literal("status").executes { checkStatus(it.source) })
                .then(literal("sweep").executes { triggerManualSweep(it.source) })
                .then(literal("tick").executes { tickInfo(it.source) })
                .then(literal("tracked").executes { trackedCount(it.source) })
                .then(
                    literal("query")
                        .then(
                            argument("radius", IntegerArgumentType.integer(1, 100))
                                .executes { ctx ->
                                    testQuery(
                                        ctx.source,
                                        IntegerArgumentType.getInteger(ctx, "radius").toDouble()
                                    )
                                }
                        )
                )
                .then(
                    literal("query_ids")
                        .then(
                            argument("radius", IntegerArgumentType.integer(1, 100))
                                .executes { ctx ->
                                    testQueryIds(
                                        ctx.source,
                                        IntegerArgumentType.getInteger(ctx, "radius").toDouble()
                                    )
                                }
                        )
                )
                .then(
                    literal("meta")
                        .then(
                            argument("entityId", IntegerArgumentType.integer())
                                .executes { ctx ->
                                    dumpMeta(
                                        ctx.source,
                                        IntegerArgumentType.getInteger(ctx, "entityId")
                                    )
                                }
                        )
                )
                .then(
                    literal("chunks")
                        .then(
                            argument("radius", IntegerArgumentType.integer(0, 16))
                                .executes { ctx ->
                                    chunkQuery(
                                        ctx.source,
                                        IntegerArgumentType.getInteger(ctx, "radius")
                                    )
                                }
                        )
                )
                .then(literal("engine_stats").executes { engineStats(it.source) })
                .then(
                    literal("log_async")
                        .then(literal("on").executes { setLogAsync(it.source, true) })
                        .then(literal("off").executes { setLogAsync(it.source, false) })
                )
                .then(
                    literal("massspawn")
                        .then(
                            argument("number", IntegerArgumentType.integer(1, 500))
                                .then(
                                    argument("entity", StringArgumentType.word())
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
                .then(
                    literal("breadcrumbs")
                        .then(literal("enable").executes { toggleBreadcrumbs(it.source, true) })
                        .then(literal("disable").executes { toggleBreadcrumbs(it.source, false) })
                )
                .then(literal("cache_clear").executes { clearCache(it.source) })
        )
    }

    private fun checkStatus(source: CommandSourceStack): Int {
        val bc = if (breadcrumbsEnabled) "§aEnabled" else "§7Disabled"
        source.sendSuccess({ Component.literal("§7[Pacioli] Engine per-world (26.1) §7| Breadcrumbs: $bc") }, false)
        return 1
    }

    private fun triggerManualSweep(source: CommandSourceStack): Int {
        val level = source.level
        val eng = PacioliEngine.forLevel(level)
        val t0 = System.nanoTime()
        eng.safetySweep()
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val rounded = (ms * 1000.0).toInt() / 1000.0
        source.sendSuccess({ Component.literal("§7[Pacioli] Safety sweep: §f${rounded}ms") }, false)
        return 1
    }

    private fun tickInfo(source: CommandSourceStack): Int {
        val eng = PacioliEngine.forLevel(source.level)
        val serial = eng.currentTickSerial()
        source.sendSuccess({ Component.literal("§7[Pacioli] Tick serial for this level: §f$serial") }, false)
        return 1
    }

    private fun trackedCount(source: CommandSourceStack): Int {
        var n = 0
        for (e in source.level.getAllEntities()) {
            if (!e.isRemoved) n++
        }
        source.sendSuccess({ Component.literal("§7[Pacioli] Entities in level (vanilla count): §f$n") }, false)
        return 1
    }

    private fun testQueryIds(source: CommandSourceStack, radius: Double): Int {
        val eng = PacioliEngine.forLevel(source.level)
        val pos = source.position
        val t0 = System.nanoTime()
        eng.collectEntityIdsInRange(pos, radius, idQueryResults)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val rounded = (ms * 1000.0).toInt() / 1000.0
        source.sendSuccess({
            Component.literal(
                "§7[Pacioli] query_ids (metadata-only sphere): §f${idQueryResults.size} §7ids in §f${rounded}ms"
            )
        }, false)
        PacioliLog.metric("COMMAND_QUERY_IDS", ms, idQueryResults.size)
        return 1
    }

    private fun dumpMeta(source: CommandSourceStack, entityId: Int): Int {
        val snap = PacioliAPI.trackingSnapshot(source.level, entityId)
        if (snap == null) {
            source.sendFailure(Component.literal("§c[Pacioli] No tracking snapshot for id=$entityId (not loaded this tick)."))
            return 0
        }
        val move = formatMovementBits(snap.movementMask)
        val tier = healthTierName(snap.healthTier)
        val hf = buildString {
            if ((snap.healthStateMask and HealthBits.DAMAGE) != 0) append("DAMAGE ")
            if ((snap.healthStateMask and HealthBits.HEAL) != 0) append("HEAL ")
        }
        source.sendSuccess({
            Component.literal(
                "§7[Pacioli] id=§f$entityId §7tick=§f${snap.tickSerial} §7move=[§f$move§7] " +
                    "§7h=§f${snap.health}§7/§f${snap.maxHealth} §7Δ§f${snap.healthDelta} §7tier=§f$tier §7flags=§f$hf"
            )
        }, false)
        return 1
    }

    private fun chunkQuery(source: CommandSourceStack, chunkRadius: Int): Int {
        val eng = PacioliEngine.forLevel(source.level)
        val pos = source.position
        val bx = Mth.floor(pos.x)
        val bz = Mth.floor(pos.z)
        val cx = Mth.floorDiv(bx, 16)
        val cz = Mth.floorDiv(bz, 16)
        val t0 = System.nanoTime()
        eng.collectEntityIdsInChunkRadius(cx, cz, chunkRadius, idQueryResults)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val rounded = (ms * 1000.0).toInt() / 1000.0
        source.sendSuccess({
            Component.literal(
                "§7[Pacioli] Chunk §f$cx,$cz §7r=§f$chunkRadius §7→ §f${idQueryResults.size} §7ids (§f${rounded}ms§7)"
            )
        }, false)
        return 1
    }

    private fun engineStats(source: CommandSourceStack): Int {
        val eng = PacioliEngine.forLevel(source.level)
        source.sendSuccess({
            Component.literal(
                "§7[Pacioli] tickSerial=§f${eng.currentTickSerial()} " +
                    "§7tracked=§f${eng.trackedCount()} §7cells=§f${eng.spatialCellCount()} " +
                    "§7chunkCols=§f${eng.chunkColumnCount()}"
            )
        }, false)
        return 1
    }

    private fun setLogAsync(source: CommandSourceStack, on: Boolean): Int {
        PacioliLog.ASYNC_ENABLED = on
        source.sendSuccess({ Component.literal("§7[Pacioli] Async log sink: ${if (on) "§aON" else "§cOFF"}") }, false)
        return 1
    }

    private fun formatMovementBits(mask: Int): String = buildList {
        if ((mask and MovementBits.STATIONARY) != 0) add("STATIONARY")
        if ((mask and MovementBits.MOVING) != 0) add("MOVING")
        if ((mask and MovementBits.FAST) != 0) add("FAST")
        if ((mask and MovementBits.AIRBORNE) != 0) add("AIRBORNE")
        if ((mask and MovementBits.FALLING) != 0) add("FALLING")
        if ((mask and MovementBits.DIR_CHANGE) != 0) add("DIR_CHANGE")
    }.joinToString(",").ifEmpty { "—" }

    private fun healthTierName(tier: Int): String = when (tier) {
        HealthTier.NONE -> "NONE"
        HealthTier.HEALTHY -> "HEALTHY"
        HealthTier.INJURED -> "INJURED"
        HealthTier.CRITICAL -> "CRITICAL"
        HealthTier.NEAR_DEATH -> "NEAR_DEATH"
        else -> "?"
    }

    private fun testQuery(source: CommandSourceStack, radius: Double): Int {
        val eng = PacioliEngine.forLevel(source.level)
        val pos = source.position
        val t0 = System.nanoTime()
        eng.getEntitiesInRange(pos, radius, queryResults)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val rounded = (ms * 1000.0).toInt() / 1000.0
        source.sendSuccess({ Component.literal("§7[Pacioli] Found §f${queryResults.size} §7entities in §f${rounded}ms") }, false)
        PacioliLog.metric("COMMAND_QUERY", ms, queryResults.size)
        return 1
    }

    private fun toggleBreadcrumbs(source: CommandSourceStack, enabled: Boolean): Int {
        breadcrumbsEnabled = enabled
        PacioliLog.info("BREADCRUMBS", "Diagnostic trails set to: $enabled")
        source.sendSuccess({ Component.literal("§7[Pacioli] Breadcrumbs ${if (enabled) "§aEnabled" else "§cDisabled"}") }, false)
        return 1
    }

    private fun clearCache(source: CommandSourceStack): Int {
        PacioliEngine.forLevel(source.level).clearCache()
        source.sendSuccess({ Component.literal("§7[Pacioli] Spatial cache cleared for this level.") }, false)
        return 1
    }

    private fun spawnMass(source: CommandSourceStack, number: Int, entityName: String) {
        val world = source.level
        val pos = source.position
        val random = ThreadLocalRandom.current()

        val id = Identifier.tryParse(entityName.lowercase()) ?: run {
            source.sendFailure(Component.literal("§c[Pacioli] Invalid identifier: $entityName"))
            return
        }

        val type = BuiltInRegistries.ENTITY_TYPE.getValue(id) ?: run {
            source.sendFailure(Component.literal("§c[Pacioli] Entity '$id' not found."))
            return
        }

        repeat(number) {
            val angle = random.nextDouble() * 2 * Math.PI
            val dist = random.nextDouble() * 8.0
            val sx = pos.x + Math.cos(angle) * dist
            val sy = pos.y + 1.0
            val sz = pos.z + Math.sin(angle) * dist

            val entity = type.create(world, EntitySpawnReason.COMMAND) ?: return@repeat
            entity.moveOrInterpolateTo(Vec3(sx, sy, sz), entity.yRot, entity.xRot)
            entity.setDeltaMovement(
                (random.nextDouble() - 0.5) * 1.5,
                0.3,
                (random.nextDouble() - 0.5) * 1.5
            )
            world.addFreshEntity(entity)
        }
        PacioliLog.info("COMMAND", "SpawnMass: $number x $id")
        source.sendSuccess({ Component.literal("§7[Pacioli] Summoned §f$number §7of §f$id") }, false)
    }
}
