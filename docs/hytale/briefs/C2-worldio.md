# C2 — Hytale world/block I/O cell (candidate worker brief)

You are a bounded candidate worker. Parent = Hermes (Venix), owns acceptance. You implement ONLY `hytale/src/main/java/network/vonix/guardian/hytale/world/**` in `C:\Users\Pargon\DEV\vonixguardian-hytale`.

## Read first
1. `docs/hytale/HYTALE-PORT-CONTRACT.md` (pin, verified API facts, contract, gates).
2. `hytale/src/main/java/network/vonix/guardian/hytale/spi/{WorldBlockAccess,BlockSnapshot}.java` — the frozen interface you implement.
3. `core/src/main/java/network/vonix/guardian/core/rollback/WorldMutator.java` — the second interface you implement (all `try*` paths must return honest success/failure).
4. Reference: `mc-26.3/fabric/src/main/java/network/vonix/guardian/mc/v26_3/fabric/FabricWorldMutator.java`.

## Mission
This is the highest-uncertainty cell — the block read/write API must be discovered from the jar, not guessed:
- `world/HytaleBlockAccess implements spi.WorldBlockAccess` — world id mapping (`World` UUID ↔ stable engine id), `read`, `write` with read-back verification, `isChunkLoaded`, `clearToAir` that also drops block component data the new block does not own.
- `world/HytaleWorldMutator implements core.rollback.WorldMutator` — `trySetBlock`, NBT/state variant, `tryGiveOrDrop`, clear-to-air; every method returns `false` on unknown world, unloaded chunk, unknown block id, failed mutation, or read-back mismatch.
- `world/HytaleWorlds` — lookup of live `World` instances and server-thread dispatch helper (`World#execute(Runnable)`).

## API discovery you must do (record the exact signatures in your result)
```bash
JDK="C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin"
JAR="C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar"
"$JDK/jar" tf "$JAR" | grep -E "^com/hypixel/hytale/server/core/universe/world/(chunk|storage)/" | head -60
"$JDK/javap" -p -cp "$JAR" com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
"$JDK/javap" -p -cp "$JAR" com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk
"$JDK/javap" -p -cp "$JAR" com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations
"$JDK/javap" -p -cp "$JAR" com.hypixel.hytale.server.core.universe.world.World
"$JDK/javap" -p -cp "$JAR" com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType
```
Find the real get/set-block, rotation and per-position component (block entity) accessors. If the shapes you find contradict this brief, say `PREMISES=` in your result with the real signatures — do not silently adapt.

## Rules
- All mutation on the owning world's server thread; document the thread contract in javadoc.
- A `true` return is only after read-back matches. Never fabricate `AIR` for an unloaded position.
- Never modify anything outside your package (except your result file). No commands, no capture listeners, no config.
- Do not run `gradle`/`gradlew` (parent-owned).

## Gate you must run
```bash
cd /c/Users/Pargon/DEV/vonixguardian-hytale
bash scripts/hytale-compile-check.sh hytale/src/main/java/network/vonix/guardian/hytale/world
```
`COMPILE_GATE=PASS` required.

## Deliverable
`docs/hytale/results/world.md` in contract §6 format, including the verified block-API signatures and every unproven runtime assumption (e.g. "rotation round-trip unverified without a live server").
