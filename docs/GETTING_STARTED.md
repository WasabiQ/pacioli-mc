# Getting started with Pacioli

Pacioli is a **library mod** for high-performance spatial queries and per-tick entity snapshots on the dedicated or integrated server.

## Dependency

Add Pacioli as a **mod dependency** in your `fabric.mod.json` and Gradle/Maven setup (same Minecraft and Fabric API versions as Pacioli).

## Threading rules

1. Call [PacioliAPI.engineFor](src/main/kotlin/wasabi/paciolimc/core/PacioliAPI.kt)`(ServerLevel)` only from the **server thread**.
2. After each server tick, the engine exposes a consistent snapshot. Query with:
   - `engine.collectEntityIdsInRange(origin, radius, outIds)` — **no** live position reads in the cull phase.
   - `engine.trackingMetadata(id)` or `PacioliAPI.trackingSnapshot(level, id)` for movement/health bits.
3. Use [PacioliAsync](src/main/kotlin/wasabi/paciolimc/concurrent/PacioliAsync.kt) for file/network work; apply results back on the server thread (e.g. next tick or scheduled task).

## Custom mob AI (typical flow)

```kotlin
val engine = PacioliAPI.engineFor(level)
val buffer = ArrayList<Int>(512)
engine.collectEntityIdsInRange(mobPos, 32.0, buffer)
for (i in 0 until buffer.size) {
    val id = buffer[i]
    val meta = engine.trackingMetadata(id) ?: continue
    if ((meta.movementMask and MovementBits.FAST) != 0) {
        // react to fast-moving target using cached state only
    }
}
```

## Post-tick hooks

Register a callback that runs **after** commit for that level:

```kotlin
PacioliAPI.registerPostTickHook { level ->
    // read-only engine queries; optional cheap bookkeeping
}
```

## Client presentation

On the client, use [PresentationHooks](src/client/kotlin/wasabi/paciolimc/client/PresentationHooks.kt) to load assets on virtual threads and apply on the main thread.

## Debug commands (integrated server, dev)

- `/pacioli query <r>` — range query (resolves entities).
- `/pacioli query_ids <r>` — metadata-only id query.
- `/pacioli meta <entityId>` — snapshot dump.
- `/pacioli chunks <chunkRadius>` — chunk-column grid query.
- `/pacioli engine_stats` — tracked count and index sizes.
