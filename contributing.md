# Contributing to Pacioli

Pacioli targets **Minecraft 26.1**, **Fabric**, **JDK 25**, and **Kotlin**.

## Principles

- **Server thread**: [PacioliEngine](src/main/kotlin/wasabi/paciolimc/engine/PacioliEngine.kt) runs snapshot → derive → commit on the server game thread. Do not mutate engine state from virtual threads or the client thread.
- **Hot paths**: Spatial queries should use cached [TrackingMetadata](src/main/kotlin/wasabi/paciolimc/engine/TrackingMetadata.kt) ([collectEntityIdsInRange](src/main/kotlin/wasabi/paciolimc/engine/PacioliEngine.kt)); avoid live `Entity` position reads in AI loops.
- **Stable API**: Prefer exposing behavior through [PacioliAPI](src/main/kotlin/wasabi/paciolimc/core/PacioliAPI.kt) so dependent mods have a small surface area.

## Build

```bash
./gradlew build
```

## Style

- Match existing naming and package layout (`wasabi.paciolimc.*`).
- Keep changes focused; avoid unrelated refactors in the same PR.

## Loom and Panama

- **Loom** ([PacioliAsync](src/main/kotlin/wasabi/paciolimc/concurrent/PacioliAsync.kt), [PacioliLog](src/main/kotlin/wasabi/paciolimc/core/PacioliLog.kt)): use virtual threads for blocking I/O only.
- **Panama** ([PanamaBuffers](src/main/java/wasabi/paciolimc/nativex/PanamaBuffers.java)): use FFM only where profiling justifies it; document lifetimes (`Arena`).
