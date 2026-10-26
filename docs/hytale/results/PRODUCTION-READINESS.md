# Production readiness — Hytale port

Review date: 2026-10-23. Read-only. No Gradle, no deploy, no source edits except this file.
Pin: Hytale Server `0.7.4` (`C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar`), engine `mod_version=2.2.1` (`gradle.properties`).
Line numbers are the working tree as read on this date. A parallel editor can move them.
Parent (Hermes) owns acceptance. This verdict is evidence.

## 1. Verdict

**NOT_READY**

Blocking before any CurseForge 1.0.0 listing:

1. Rollback is proven only as "the mutator’s own on-thread read-back returned true" on world `default`. It is not proven that a player sees the restored blocks. The flag the port added to "notify clients" does not notify clients (jar, below).
2. Post-rollback verification has already reported meaningless numbers on a live server, and the current checker still treats a 100% unreadable run as clean.
3. One real `BLOCK_BREAK` inverse failure leaves `vg_rollback_batches` OPEN. Recovery does not replay it. The same action id failed twice.
4. Block-entity / container bytes are never captured. A rollback that clears or replaces such a block drops contents that have no audit row.
5. The port is affine to `Universe.getDefaultWorld()` for every engine mutation. Any other world fails closed.
6. The manifest, help text, and version story overclaim what the jar can do. `CHANGELOG.md` has no Hytale section. Two different candidate sha256 values are already on record.

### Live lines the brief asked about

The brief’s exact batch is **not** in the retained test-server logs (`C:\Users\Pargon\DEV\hytale-testserver`). Searched `*.log` for `applied=1064`, `checked=1046`, and `unverifiable`. No hits. Do not treat 1064/1046 as a row this tree produced. What is on disk is the same code path at smaller scale, plus a later verifier format.

**(a) One mutation failed and left the batch PARTIAL — supported, for the batches that exist.**

`RollbackEngine.finishBatch` sets `PARTIAL` when `failed` is non-empty and `applied` is non-empty, then refuses to close the row and logs the exact "left OPEN" line (`core/.../RollbackEngine.java:453-484`). `applyMutation` returns failed when the mutator boolean is false, and logs `mutation unsuccessful for action id={} type={}` (`RollbackEngine.java:1288-1294`). `BLOCK_BREAK` inverse is `trySetBlock` (`RollbackEngine.java:1334-1340`). There is no exception text on these lines, so `trySetBlock` returned false.

Retained log `logs/2026-10-22_19-13-24_server.log`:

- 23:47:04 action `176` `BLOCK_BREAK` unsuccessful, then `batch id=1 left OPEN status=PARTIAL applied=23 skipped=0 failed=1`.
- 23:49:03 actions `303` and `176` unsuccessful, then `batch id=2 left OPEN status=PARTIAL applied=141 skipped=0 failed=2`.

Action 176 failed on two separate rollbacks. That is a sticky mutator false, not a one-shot off-thread glitch of the whole batch: the rest of each batch applied. `trySetBlock` returns false for a blank id, oversized component bytes, conflicting filler, unparsable rotation, a failed `write`, or a failed interaction-state restore (`HytaleWorldMutator.java:100-140`). The Hytale log does not say which. `recoverIncompleteBatches` only logs open ids (`RollbackEngine.java:565-593`). It does not replay. Successful ids in the same batch are still `markRolledBack` (`RollbackEngine.java:492-497`).

**(b) The verifier’s 1046/1046 unverifiable reads were off-thread nulls — supported for that older checker. Those numbers are not evidence the chunks were unreadable on the world thread.**

`HytaleBlockAccess.sectionAt` returns null when `World.isInThread()` is false (`HytaleBlockAccess.java:305-309`). `World.execute` always `Deque.offer`s and never runs inline (`javap -c` of `World.execute` on Server 0.7.4: if `acceptingTasks` is false it throws `SkipSentryException` wrapping `IllegalThreadStateException`; otherwise `taskQueue.offer` and return). An off-thread `read` cannot see the section.

Current `WriteVerifier` javadoc says an earlier version reported 1046/1046 unverifiable for that reason (`WriteVerifier.java:24-28`). Current `summary()` always appends `passes=` (`WriteVerifier.java:77-85`). The brief’s line has `unverifiable=1046` and no `passes=`, so it is that earlier checker, not the method on disk now.

A later format is also on disk and is also not current `summary()` (no `unverifiable`, no `passes=`). `logs/2026-10-23_02-45-38_server.log`, world `default`, same second as the engine result, no second world-thread task between them:

| time | engine | verifier |
|---|---|---|
| 06:56:40 | success=true applied=388 worldKey=default | checked=383 mismatched=383 repaired=0 unfixed=383 |
| 06:56:52 | success=true applied=237 | checked=234 mismatched=234 repaired=0 unfixed=234 |
| 07:00:00 | success=true applied=853 | checked=811 mismatched=811 repaired=0 unfixed=811 |
| 07:01:11 | success=true applied=463 | checked=449 mismatched=449 repaired=0 unfixed=449 |
| 07:01:56 | success=true applied=379 | checked=369 mismatched=369 repaired=0 unfixed=369 |
| 07:02:02 | success=true applied=103 | checked=103 mismatched=103 repaired=0 unfixed=103 |

The engine task is logged as `RollbackEngine` on `[World|default]` (88ms at 06:56:40, 50ms at 07:01:11). The verifier line is immediate and has no matching world-thread task. That fits reads and repair writes on the command worker: `sectionAt` null, counted as mismatch, repair `write` also false, `repaired=0`. It does not fit the current `converge`, which hops through an `Executor` and would print `unverifiable` and `passes=`.

`clean()` still ignores `unverifiable` (`WriteVerifier.java:73-75`). A rerun of the brief’s 1046/1046 outcome on the current checker would be reported as clean, and `FilterMutationCommand` only warns the player when `!clean()` (`FilterMutationCommand.java:128-131`).

The applied-vs-checked gap (388/383, 853/811, 103/103) fits `recordPending` running only after a successful `write` (`HytaleBlockAccess.java:188-190`) and not after `clearToAir` (`HytaleBlockAccess.java:207-238`). 103=103 only means that batch recorded every applied mutation. It does not prove clears are recorded.

**(c) After removing a client-notify regression, rollback restores in-game — not proven.**

`SetBlockSettings` on the 0.7.4 jar: `NO_NOTIFY=1`, `FORCE_CHANGED=64`, `PERFORM_BLOCK_UPDATE=256`. The port ORs the last two (`HytaleBlockAccess.java:476-479`). In `BlockOperations.setBlock` the 256 bit is `sipush 256; iand; ifeq` then `updateBlockArea` (disassembly `BlockOperations.javap.txt` offsets 561-587). `updateBlockArea` calls `BlockSection.setTicking` in the same section, or `ChunkStore.getChunkSectionReferenceAtBlockAsync(...).thenAccept` for a different section (offsets 0-62 of that method). That is neighbor ticking, including an async cross-section path. It is not a client packet. `NO_NOTIFY` is not tested in that `setBlock` body. The class that drains changed block positions and sends them to clients was not identified; a full-jar string scan was not completed. Do not claim the client saw the restore.

What the later logs do prove: on world `default`, `write`’s own read-back (block id, rotation index, filler — `HytaleBlockAccess.java:56-58`) returned true for every applied mutation (`success=true`). That is server palette agreement at the moment of the write. The verifier lines next to them do not overturn that, because those lines were not produced by an on-thread read. They also do not confirm the client.

## 2. Port functionality matrix

`IMPLEMENTED+PROVEN` means a live 0.7.4 session showed the behavior, not merely that the method exists. Cell docs under `docs/hytale/results/` are compile-time evidence unless a log line is cited.

| Feature | Status | Evidence |
|---|---|---|
| Block break capture | IMPLEMENTED+UNPROVEN | `HytaleBlockCapture.BreakByPlayer` (`HytaleBlockCapture.java:50-108`) submits `BLOCK_BREAK` with the `PlayerRef` actor. Live log only shows the skip path (`Debug_Explosive`, reaction `Exploding`, `2026-10-23_02-45-38_server.log:925`). Rows existed later because rollback applied hundreds of them. Per-row id, actor, and rotation were not checked. |
| Block place capture | IMPLEMENTED+UNPROVEN | `PlaceByPlayer` (`HytaleBlockCapture.java:122-160`) takes the id from the held item (`:147`). No live place row was inspected. |
| Environment capture | IMPLEMENTED+UNPROVEN | `HytaleEnvironmentCapture.java:70-90` writes one `BLOCK_BREAK`, actor `Sentinel.UNKNOWN`, source tag `environment`. Fire vs explosion vs physics is not on the event (`javap`: `EnvironmentBreakBlockEvent` has `targetBlock` and `blockType` only, extends `EcsEvent`). |
| Explosion capture | MISSING | No `submitExplosion` call under `hytale/`. Environment breaks are not explosions. |
| Container / inventory deltas | MISSING | `CaptureRegistration.admit` (`CaptureRegistration.java:213-246`) stores a rotation string on `targetMeta` / block-state when `persistNbt` is on. No container event, no item bytes. |
| Entity kill | MISSING | No kill system. `HytaleWorldMutator` does not override `tryRemoveEntity` / `tryRespawnEntity`. Defaults return false (`WorldMutator.java:191-212`). |
| Lookup | IMPLEMENTED+UNPROVEN | Subcommand exists (`HelpCommand.java:27`). No successful lookup transcript was tied to a row in the logs read here. |
| Inspect | MISSING | `InspectMode` is a flag (`InspectMode.java:10-27`). Nothing under `hytale/` calls `isInspecting` except `InspectMode` itself. Minecraft lanes do (`mc-26.3/fabric/.../FabricEvents.java:291`). Help still advertises it (`HelpCommand.java:26`). |
| Rollback | IMPLEMENTED+UNPROVEN | Live `success=true` on world `default` (table above) and live `PARTIAL` (22 Oct log). Client visibility unproven. Post-check did not converge. |
| Restore | IMPLEMENTED+UNPROVEN | Same class as rollback (`FilterMutationCommand.java:38-49`). No restore line in the logs read here. |
| Undo | IMPLEMENTED+UNPROVEN | Pushed only when `!preview && mutatedWorld` (`FilterMutationCommand.java:132-133`). No live undo line in the logs read here. |
| Purge | IMPLEMENTED+UNPROVEN | Listed in help (`HelpCommand.java:31`). `command.md` records that console mutation is denied, so a console purge cannot pass the in-cell gate. Not re-exercised here. |
| Permissions | IMPLEMENTED+UNPROVEN | Generated Hytale node plus `PlayerDirectory` double-check is the command cell’s account (`docs/hytale/results/command.md` lines 10-12). Player `Pargon` ran `/vg rollback` successfully. Denial paths and non-wildcard grants were not exercised in the lines read here. `HytaleOpLookup` returns 4 or 0 only (`HytaleOpLookup.java:36-49`). |
| Config load | IMPLEMENTED+PROVEN | `start()` loads via `HytaleConfigLoader.load` (`VonixGuardianPlugin.java:66`). Parent live boot recorded `config=mods\Vonix_VonixGuardian\config.json` and sqlite (`docs/hytale/results/PARENT-GATES.md:43-47`). That boot jar is sha256 `469a3288…`, not the candidate `90e0da3c…` in the same file’s header. |
| Per-world config | IMPLEMENTED+UNPROVEN | Core wires it only when `<dataDir>/config/vonixguardian/worlds` is a directory (`Guardian.java:305-310`). No Hytale command edits it. |
| i18n / messages | MISSING | No language or message-bundle use under `hytale/`. Player text is English literals (`HelpCommand.java:22-35`, `FilterMutationCommand.java:59`). |
| Database sqlite | IMPLEMENTED+PROVEN | Fat jar bundles sqlite (`hytale/build.gradle:50`). `Class.forName("org.sqlite.JDBC")` on the plugin loader (`VonixGuardianPlugin.java:155-170`). Parent boot logged sqlite schema v9. |
| Database mysql / postgresql | MISSING | Not in the fat jar (`hytale/build.gradle:46-50`). Selecting them throws `SQLException` from `ensureDatabaseDriver` (`VonixGuardianPlugin.java:167-169`). Boot is contained (`VonixGuardianPlugin.java:103-108`), so the server stays up and the audit stays off. |
| Log file | IMPLEMENTED+UNPROVEN | Core opens `JsonLinesLogFile` when `config.logFile().enabled()` (`Guardian.java:217-225`). Nothing under `hytale/` mentions it. Not seen in the log lines read here. |
| Attribution, player break/place | IMPLEMENTED+UNPROVEN | Actor is the `PlayerRef` on the event entity (`HytaleBlockCapture.java:79-84`, `CaptureRegistration.java:125-146`). |
| Attribution, environment | IMPLEMENTED+UNPROVEN | Coarse `#unknown` / `environment` only (`HytaleEnvironmentCapture.java:26-28, 89-90`). |
| Attribution, damage / explosion / entity | MISSING | `HytaleDamageCapture.hasProvenPlayerCauser` always returns false (`HytaleDamageCapture.java:32-38`). `javap` of `DamageBlockEvent`: `itemInHand`, `targetBlock`, `blockType`, `currentDamage`, `damage`. No player. No damage system is registered (`CaptureRegistration.java:71-74` only logs the gap). |

Container mutator methods exist and are unreachable from capture: `tryGiveOrDrop` / `tryRemoveFromContainer` (`HytaleWorldMutator.java:164-208`). Player-inventory removal stays at the core default (`WorldMutator.java:163-176`).

## 3. Defects

### P0 — data loss

**P0-1. Rollback of a block entity drops contents that were never logged.**
`CaptureRegistration.admit` (`CaptureRegistration.java:213-246`) persists rotation text only. `clearToAir` documents that `BlockOperations.setBlock` of `BlockType.EMPTY` clears a block entity in that same call when the new type does not own one (`HytaleBlockAccess.java:199-205`). `tryClearBlock` is the `BLOCK_PLACE` inverse (`RollbackEngine.java:1332-1333`, `HytaleWorldMutator.java:149-151`). Restoring a broken container via `trySetBlock` writes id and rotation, and component bytes only if the row already has them (`HytaleWorldMutator.java:122-125`). A chest the player filled after placing it loses that inventory on place-rollback, and a broken chest comes back empty, with no `CONTAINER_*` row to undo from.
Fix: until capture stores component bytes and container deltas, refuse `write` / `clearToAir` when the position has a block entity, and say so in the command result. Do not advertise container audit.

### P1 — wrong audit or wrong rollback

**P1-1. Default-world executor is the only mutation landing spot.**
`VonixGuardianPlugin.start` passes `HytaleExecutors.serverThread(VonixGuardianPlugin::defaultWorld)` into `Guardian.boot` (`VonixGuardianPlugin.java:76-88`). `RollbackEngine` runs each batch on that single executor (`RollbackEngine.java:1072`). `HytaleExecutors` already says a mutation for a different world must be scheduled on that world’s executor (`HytaleExecutors.java:22-26`). `sectionAt` fail-closes off-thread (`HytaleBlockAccess.java:305-309`), so every mutation on a non-default world returns false, the batch stays OPEN, and the successes on `default` are still marked rolled back.
Fix: resolve `World` from the action’s `worldId` and `execute` on that world. Never wait for world B’s queue from inside world A’s `execute`.

**P1-2. `WriteVerifier.clean` ignores unreadable positions, and the call site cannot verify any world but the default.**
`clean()` is `mismatched == 0 && dropped == 0 && !timedOut` (`WriteVerifier.java:73-75`). `unverifiable` is not in it. `FilterMutationCommand` hops the checker through `services.mainThread()` (`FilterMutationCommand.java:125-127`), which is the same default-world executor. A null read is kept as outstanding and later counted unverifiable (`WriteVerifier.java:172-174`, `218-232`), then the run can still be clean. `sameState` compares block id and rotation index only (`WriteVerifier.java:268-275`). Filler and component bytes are ignored. `clearToAir` never calls `recordPending`.
Fix: `clean()` must be false when `unverifiable > 0` or `unfixed > 0`. Run the pass on the world that owns each position. Record clears. Compare filler and component bytes, or stop claiming convergence.

**P1-3. `PERFORM_BLOCK_UPDATE` ticks neighbors during rollback.**
Comment at `HytaleBlockAccess.java:476-478` says the flag pushes the change to clients. The jar says it calls `updateBlockArea` → `setTicking`, with an async path for other sections (section 1). The port never sets `NO_NOTIFY`. Live sessions around these rollbacks are full of `ExplosiveSystems$Ticking` on `[World|default]` (same log, 06:56:01 through 07:01:47, both before and after the commands). Causation is not isolated. The mechanism is: a rollback can schedule ticks, and a tick can change blocks the audit did not ask to change, including off the world thread via `thenAccept`.
Fix: stop OR-ing `PERFORM_BLOCK_UPDATE` until a player-visible restore is proven by the real dirty-position flush. Do not substitute this flag for that flush.

**P1-4. A single `trySetBlock` false leaves the batch OPEN with no reason, and the same id is retried forever.**
Evidence in section 1(a). Action 176 failed twice. `recoverIncompleteBatches` does not close or replay (`RollbackEngine.java:565-569`). The operator gets `mutation unsuccessful` and no coordinate or `trySetBlock` branch.
Fix: log world, x, y, z, target id, and the branch that returned false. Surface the open batch id on the command reply, not only in the log.

**P1-5. Inspect is advertised and does nothing.**
`InspectMode.java:13-16` says capture can call `isInspecting`. Capture does not. `HelpCommand.java:26` tells the player the toggle works. Minecraft `FabricEvents` consults `Inspector` before logging a click (`mc-26.3/fabric/.../FabricEvents.java:291`).
Fix: either consult the flag in `HytaleBlockCapture` before `admit`, or remove the subcommand and the help line.

**P1-6. Help says `#preview` then "run the same filter again without `#preview`" applies.**
`HelpCommand.java:34`. `FilterMutationCommand` plans and executes the filter it was given (`FilterMutationCommand.java:105-108`). There is no preview store. `#preview` is a flag on that invocation, not a held plan. `command.md` already records that `--confirm` is the gate.
Fix: delete that sentence. Say `#preview` and `#count` are per invocation, and `--confirm` is what applies.

**P1-7. Break rotation is dropped when the section already shows a different id.**
`priorRotation` returns null when the live block id disagrees with the event (`CaptureRegistration.java:184-195`). `admit` then uses `submitBlockBreak`, which cannot store `targetMeta` (`CaptureRegistration.java:222-224`). The broken block is logged without rotation. Rollback restores the default rotation.
Fix: take rotation from the event or from a snapshot captured before the break commits. Do not read the position after it may already have changed and then discard the only rotation you had.

### P2 — robustness

**P2-1. Explosive skip is any non-blank `getExplosionReactionState`.**
`HytaleBlockCapture.java:85-95`. Live evidence is `Debug_Explosive` with reaction `Exploding` (log line cited above). The comment says "thrown barrel" and claims destroyed terrain is still covered by environment rows. Those rows are `#unknown` breaks, not an explosion list. A full asset census was not finished; over-match against ordinary terrain is unproven. Treat the predicate as wider than the comment until the census is done.
Fix: allow only the reaction values that mean a detonated consumable, after enumerating `ExplosionReactionState` in the asset pack.

**P2-2. Capture systems are installed on the global `EntityStore.REGISTRY`.**
`CaptureRegistration.java:38-39, 71-74`. `hasSystemClass` avoids a second system in one class loader. A new plugin class loader can register again. `REGISTERED` is also per loader (`CaptureRegistration.java:43, 65-67`).
Fix: register on the plugin’s `getEntityStoreRegistry()` proxy so unload removes the systems.

**P2-3. Command workers are not stopped, and a second `register` builds another pool.**
`CommandRuntime` constructs `BoundedGenerationExecutor("VonixGuardian-HytaleCmd", 64, 2)` (`CommandRuntime.java:56-57`). `VonixGuardianPlugin.shutdown` drains the engine queue and closes it (`VonixGuardianPlugin.java:112-131`) and does not stop that pool. `CommandRegistration.register` has no once-guard (`CommandRegistration.java:22-33`). Each `GuardianCommand` owns a runtime.
Fix: one runtime for the plugin life, shut the executor down in `shutdown`.

**P2-4. Convergence can stall the default world for 20s per pass.**
`PASS_TIMEOUT_MILLIS` is 20_000 and the default is 4 passes plus a classify pass (`WriteVerifier.java:43-49, 124-150`). The pass runs on the world thread and also re-writes mismatches with `PERFORM_BLOCK_UPDATE` (`WriteVerifier.java:186-188`). Called from the command worker (`FilterMutationCommand.java:79`), so it does not deadlock that caller. It does occupy the tick thread.
Fix: cap the positions per pass, or drop the in-tick repair until P1-2 is fixed.

**P2-5. Chat hop falls back onto the caller thread.**
`CommandRuntime.hop` runs the send inline when the executor is missing or `execute` throws (`CommandRuntime.java:512-529`). `World.execute` throws when the world is not accepting tasks (jar, section 1).
Fix: drop or retry the message. Do not call `CommandSender.sendMessage` from `VonixGuardian-HytaleCmd` after a failed hop.

**P2-6. Mysql and postgresql are selectable and unpackaged.**
Section 2. The failure is contained, which is the right boot behavior, and it will look like "plugin failed to start" with no driver in the jar.
Fix: reject those types in `HytaleConfigLoader` with a message that names the missing driver, or shade the drivers before documenting them.

### P3 — polish

**P3-1. Manifest overclaims.**
`hytale/src/main/resources/manifest.json:5` says "block, container and entity audit". Container and entity audit are missing. `ServerVersion` is `*` (`manifest.json:15`). `Version` is `${version}` expanded from `2.2.1`, not a Hytale `1.0.0`.

**P3-2. No changelog entry.**
`CHANGELOG.md` has no `Hytale` match.

**P3-3. Class javadoc on `HytaleBlockAccess` still says the write flag is `FORCE_CHANGED` alone** (`HytaleBlockAccess.java:54-55`) while `setBlock` ORs `PERFORM_BLOCK_UPDATE` (`:479`).

**P3-4. Op levels 1–3 cannot occur.**
Documented and intentional (`HytaleOpLookup.java:49-53`). Any core path that treats "op level ≥ 1" as a partial grant will see only full admin (`*`) or nothing.

## 4. Thread-safety audit

Each `World` is its own tick thread (`HytaleExecutors.java:18`). `World.execute` always enqueues (jar). Waiting for that queue from the same world thread deadlocks. Waiting for world B from inside world A’s `execute` deadlocks both tick threads (`HytaleExecutors.java:25-26`).

| Call site | Thread it runs on | `World.execute` required? | Failure if wrong |
|---|---|---|---|
| `BreakByPlayer.handle` / `PlaceByPlayer.handle` (`HytaleBlockCapture.java:60-66, 132-137`) | Not proven from the jar. The port assumes the entity-store invoke is the acting world’s thread, because it calls `read` inline for rotation (`CaptureRegistration.java:189`). | Must not wait on `execute`. `submit` is non-blocking. | Off-thread `read` returns null and the row is stored with no rotation (`priorRotation` → null). Not a deadlock. |
| `OnEnvironmentBreak.handle` (`HytaleEnvironmentCapture.java:61-65`) | Same assumption. | Same. | Same silent rotation drop. |
| `CommandRuntime.capture` (`CommandRuntime.java:195-209`) | Command dispatch thread. `command.md` says that thread is unverified. `worldIdOf` only reads `Universe` (`HytaleBlockAccess.java:103-109`). | No, for the map lookup. | `PlayerRef.getTransform` off the world thread is not javap-proven. A racy position becomes the radius center. |
| `FilterMutationCommand.run` (`FilterMutationCommand.java:79-83`) | `VonixGuardian-HytaleCmd` worker, not the world thread. | The worker may block. The command thread returns immediately (`:80`). | Safe against the "wait on own `execute`" deadlock. |
| `RollbackEngine` batch (`RollbackEngine.java:1072`) | Whatever `mainThreadExecutor` is. Today that is `defaultWorld()` (`VonixGuardianPlugin.java:76`). | Yes. The engine already hops, then the worker waits for the future. | Non-default world: every `try*` false (P1-1). If a future caller invokes `engine.execute` on the world thread, `execute`+wait deadlocks. |
| `WriteVerifier.converge` (`WriteVerifier.java:168-206`) | Caller is the command worker. Body is `worldThread.execute`, then `CountDownLatch.await` up to 20s. | Yes, and the await must not be on that world’s thread. | On the world thread: deadlock, because `execute` will not run the task until the await returns. On the wrong world: 100% null reads, and `clean()` can still be true (P1-2). This is the live bug class behind the brief’s 1046 unverifiable line. |
| `CommandRuntime.hop` (`CommandRuntime.java:512-529`) | Worker calls `defaultWorld.execute` and does not wait. | Enqueue only. | Fallback runs `sendMessage` on the worker (P2-5). Awaiting this hop from the world thread would deadlock. |
| `VonixGuardianPlugin.shutdown` (`VonixGuardianPlugin.java:112-131`) | Plugin shutdown thread. `awaitQueueDrained(5s)` then `close`. | No world wait. | In-flight world tasks can still be queued after close. The command pool keeps running (P2-3). |
| `CaptureRegistration.register` (`CaptureRegistration.java:61-77`) | `start()`, before worlds may exist. | No. | Fine. Systems fire later. |

`HytaleWorlds.runOnWorldThread` runs inline when `isInThread` (`HytaleWorlds.java:93-100`). `HytaleWorlds.execute` always queues (`:83-86`). The verifier and the engine use the always-queue executor. Do not "fix" an off-thread read by calling `execute` and joining from a system that might already be on that world.

## 5. Unproven claims

| Claim | Where | What the jar or the logs actually show |
|---|---|---|
| `PERFORM_BLOCK_UPDATE` pushes the write to clients. Without it, clients keep the old block. | `HytaleBlockAccess.java:476-478` | Bit 256 calls `updateBlockArea` → `setTicking` or an async section lookup. Not a packet. |
| Writes use `FORCE_CHANGED` only. | `HytaleBlockAccess.java:54-55` | `setBlock` ORs bit 256 (`:479`). |
| The checker learned from 1046/1046 and now reads on the owning world’s thread. | `WriteVerifier.java:24-31` | The hop executor is the default world only. `clean()` would hide a repeat of that outcome. The live 06:56 lines are an older format that reported 100% mismatch with 0 repairs, in the same second as the engine result, with no verifier task on `[World|default]`. |
| Capture will call `InspectMode.isInspecting`. | `InspectMode.java:13-16` | No such call site. |
| Any non-blank explosion reaction is a thrown barrel, and terrain is still covered. | `HytaleBlockCapture.java:85-89` | Live hit is `Debug_Explosive` / `Exploding`. Environment rows are undifferentiated `#unknown` breaks. Asset census incomplete. |
| `#preview`, then run the same filter again without `#preview`, applies. | `HelpCommand.java:34` | No preview store. Each invocation stands alone. |
| "block, container and entity audit". | `manifest.json:5` | Container and entity capture are absent. |
| All four block events extend `CancellableEcsEvent`, and `getEventRegistry().register(BreakBlockEvent.class, …)` is the path. | `docs/hytale/HYTALE-PORT-CONTRACT.md:35-37` | `javap`: `BreakBlockEvent` and `DamageBlockEvent` extend `CancellableEcsEvent`. `EnvironmentBreakBlockEvent` extends `EcsEvent` and is not cancellable. The port correctly refuses `EventRegistry.register` for these (`CaptureRegistration.java:35-39`, `VonixGuardianPlugin.java:41-45`). The contract is wrong. |
| Removing the client-notify regression made rollback restore in-game. | Brief item (c) | Not proven. See section 1. |
| `applied=1064 failed=1` and `checked=1046 unverifiable=1046`. | Brief | Not in retained `*.log`. Same mechanism as batch id=1 (`applied=23 failed=1`) and batch id=2 (`applied=141 failed=2`), and as the javadoc’s 1046 figure. |

`docs/hytale/results/world.md` saying the world cell is entirely runtime-unproven is stale. Live rollbacks on `default` returned `success=true`. Fidelity, client visibility, and post-check convergence stay unproven. `PARENT-GATES.md` header sha256 `90e0da3c…` and live-boot sha256 `469a3288…` are different jars. Gates do not transfer across them.

## 6. Release checklist for 1.0.0

All of these have to be true of the bytes that are uploaded. None of them are true of this tree today.

1. JDK 25 and Gradle 9.2.0, `-PbuildProfile=hytale`, compile and the hytale test task pass on the tree that is packaged. This review did not run Gradle.
2. One sha256. `PARENT-GATES.md` currently names two. `PLUGIN_LOAD_VERIFIED` has to be repeated on the published jar, on a licensed 0.7.4 server with assets, not on an older jar.
3. Artifact name `vonixguardian-hytale-<version>.jar` (`hytale/build.gradle:72`). `manifest.json` at jar root: `Group=Vonix`, `Name=VonixGuardian`, `Main=network.vonix.guardian.hytale.VonixGuardianPlugin`. `Version` equals the published version. Today that property is `2.2.1`. Do not retitle `2.2.1` as `1.0.0` without a changelog decision.
4. `ServerVersion` is a pin or the listing states the wildcard risk. `Description` matches the matrix: player break/place, coarse environment break, rollback/restore/undo/purge/lookup on the default world. No container or entity claim until those rows exist.
5. `CHANGELOG.md` has a Hytale section for that version.
6. A scripted round trip on world `default` and on a second world: place, break, environment break, rollback, undo. The second world either works or the listing says the port mutates the default world only. A non-default failure must not mark default-world rows rolled back.
7. Rollback safety: a clean fixture closes the batch (`status=SUCCESS`, row not OPEN). A forced single-block failure logs the action id, coordinates, and the `trySetBlock` branch, and does not mark that id rolled back. Action 176’s sticky failure is reproduced or diagnosed before ship.
8. `PERFORM_BLOCK_UPDATE` is gone, or a player on the test server is recorded seeing the restored blocks, tied to the real changed-position flush. Neighbor `setTicking` does not count.
9. The post-check either is removed or reports `unverifiable` as failure, runs on the owning world thread, and does not `await` `World.execute` from that thread. `clearToAir` is in the set it checks, or the checker is not described as covering the batch.
10. Blocks with component data are refused, or their bytes and container deltas survive a rollback and an undo.
11. Sqlite remains the only advertised backend unless mysql and postgresql drivers are inside the fat jar and a boot against each is logged.
12. Help text matches the gates: `--confirm` applies, `#preview` does not arm a later apply, inspect is implemented or absent.
13. Explosive-reaction skip is a list taken from the asset pack, not "any non-blank string".
14. Shutdown stops `VonixGuardian-HytaleCmd` and does not leave a second pool after reload.

This review found no existing CurseForge publish doc to follow. The list above is the bar, not a record of a prior publish.
