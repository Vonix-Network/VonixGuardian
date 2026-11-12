# Work report

## Status

candidate_complete

Priority items 1–6, the rollback skip count, the output layer, and the pre-existing paused-queue admission failure are committed on `feat/improvements-20261112`. The core suite on disk is green. Logging gaps and several smaller confirmed items were left undone on purpose and are listed below. No loader cell was compiled.

## What I did

1. Purge dry run. `#count` returns a count and does not delete. `#preview` returns the count plus up to eight sample rows and does not delete. Preview wins if both flags are set. Covered by a fake DAO that fails the test if `purge` or `optimize` is called. Commit `f2543f5`.

2. Config hot swap. Setting one actions value, including `actions.entityBlockChangeCoalesceWindowMs`, rebuilds `Actions` from the live record through the full constructor. Item and entity blacklists are copied. The same path covers the other actions keys that menu used to set. The old 32-argument constructor still exists and still substitutes empty lists; the command cells no longer call it. Commit `7442666`.

3. Help. `/vg help` is rendered from the command catalogue, so a subcommand cannot be omitted by a hand-written list. Undo is described as reversing the last rollback or restore and changing the world again. `/vg batch` is in that catalogue. Commit `86943db`.

4. Purge scope and time windows. Decision: a purge covers every world unless the operator named one. The caller's world is ignored. The confirmation prints `all worlds` or the world key. Rollback and lookup still default to the caller's world, and the radius default is still applied before that world default. A single `t:90d` still means "older than 90 days". A range `t:1h-2h` keeps only rows inside the window. The age floor uses the youngest timestamp that would be deleted, so `t:1d-90d` fails a 30-day floor. Console floor remains 24 hours. In-game floor remains 30 days. Commit `d07a08f`.

5. Open rollback batches. A failed rollback still leaves its batch open. Boot returns at most one operator line and logs at most one warning, including when 20 or more batches are open. `/vg batch`, `/vg batch show <id>`, and `/vg batch close <id>` are in all ten command copies, on the rollback permission. Close marks the batch finished in the database and does not change blocks, mark actions rolled back, or delete the batch's action rows. A second close changes zero rows. Rollback `#preview` and `#count` were already honoured and were left on that path. Commit `d64bc2e`.

6. `r:#worldedit`. A missing install, a missing player name, an empty selection, or an unreadable selection raises an operator-facing error instead of a query that matches zero rows. The command copies remember the player's name for that lookup. The success path needs WorldEdit on a server and was not run. Commit `421f347`.

7. Rollback honesty for stacked block edits. When a newer block row already covers the same world and coordinates, the older row is counted as skipped and is not applied again. Container, item, and entity rows at the same coordinates are still kept. Commit `22b8809`. The shared command-literal test was updated to expect `batch`, `show`, and `close`, with the reason in the test. Commit `553cbac`.

8. Output. Lookup text, rollback sentences, error details, the status headline, and theme colours are generated in `core`. Each cell only colours, attaches click or hover, and sends. Lookup shows world, amount, UTC time, and sign side, dye, and wax. `#verbose` adds slot, source, pair, block state, and whether extra data was stored. Count, preview, and a real rollback use different sentences. Zeros are omitted. Status opens with one sentence, brands only the first section, and reads the database off the server thread. Default theme text colours clear mid-grey `#3C3C3C` at 4.5:1. The old aqua secondary `#00AAAA` is asserted to fail that bar. Commit `90b8f91`.

9. Paused queue. If the worker has already taken the only queued row when pause becomes visible, the row is offered back. The next submit is rejected and journaled. If the offer fails, the row stays in the local batch rather than being dropped. Commit `79db3bc`.

10. The confirm-before-rollback source pin still searched cells for `c.rollback().requireConfirm()`, which lived only in the constructor removed in item 2. Cells now contain `rollback.requireConfirm()`, and the shared updater contains `c.rollback().requireConfirm()`. The test was changed for that reason and still requires the world-change gate. Commit `7cfce98`.

HEAD is `7cfce98`. Working tree was clean before these two reports.

## What I chose NOT to do, and why

- Logging gaps (shearing, cauldrons, cake, anvils, armor stands, item frames, jukeboxes, note blocks, wax and scrape, beds, lecterns, pots, shelves, sponge, coral, plant growth, wolves, cats, creepers). The mandate put these after the output layer and only if budget remained. A loader compile never reached this mod's sources, so a mixin port would be unverified. No cell gained mixins.
- `lookup.maxResultRows` is updated on the config record and advertised as hot-swapped, and the JDBC cap is a `final` field set when the DAO is built at boot. Changing it live does not change the cap until restart. Fixing that means rebuilding the DAO or making the cap read the live config on each query. I did not take that behavioural change without a restart story.
- Undo after a restart. The undo stack is still an in-memory map capped at 20 actors' entries. The batch tables survive, and `/vg batch` can show them, but `/vg undo` cannot replay a batch from disk. Replaying would mutate the world from a new code path. I did not add that.
- `a:+` / `a:-` as a place-only or break-only filter. The leading sign is stored and then never read by the query compiler. The token itself still selects a type when one exists (`a:+piston` selects piston extend). Applying the unused sign would change query results for family tokens. I left it and recorded the split below.
- Translation bundles. Most languages still ship a handful of keys, and command text is still English in code. A partial translation would make `language=de` a mix with no review. I did not invent translations.
- `ActionType.CLICK`. Nothing records it. Help no longer advertises a click action. I did not delete the enum value, because action type ids are stable in the database.
- Phantom blocks for `#preview`. Preview still suppresses the world edit and prints the plan. Painting ghost blocks needs a client packet per cell and was not runtime-testable here.
- Localized display names. Lookup strips `minecraft:` and shows the registry key. Core cannot call Minecraft's language files without taking a Minecraft dependency.
- Collapsing the ten command copies into one source file. The cells already share the sentences through `core`. Merging the brigadier trees is a separate refactor, and the loader compile that would prove it never got that far.
- Auto-closing a failed batch on boot. The batch stays open so an operator can see it. Close is explicit and does not touch the world.
- `docs/`. Left untouched, as required.

## Where I disagree with the brief

- `a:+` / `a:-` is not a silent no-op for every token. The parser resolves the token to an action type. What nothing reads is the stored place-only or break-only sign. Tests that only assert the stored sign still pass and do not prove the SQL uses it.
- Rollback `#preview` and `#count` already skipped the world edit before this work. The flags that deleted were on purge. I did not rework the rollback dry-run path beyond the sentences.
- The paused-queue test's expectation is right. A paused full queue must reject the next submit. The old code could take the queued row in the same instant pause became visible, free the slot, and accept another row. That matches `expected false but was true` on the third submit. The test was flaky when run alone, because the race does not always land. The new test pauses inside the poll and fails if the row is not put back.
- `/vg help` now reads the command catalogue. The catalogue is still not what builds the brigadier tree. Adding a catalogue entry does not create a command until the cell tree is updated. Both are required.
- The contrast bug is real against mid-grey chat: `#00AAAA` is about 3.85:1. No single colour is 4.5:1 against both white and mid-grey. Near-white ids clear the dark box. Daylight without a text shadow is still a weak case, and the changelog says so.
- The brief's "9 cells" under-counts the command sources. 1.18.2, 1.19.2, and 1.20.1 each have a Fabric copy and a Forge copy. Ten `GuardianCommands` files were updated. 26.2 and 26.3 are not in the eight-file structural list, and they were patched anyway.

## Verification actually run

Command, JDK 21, offline, after `7cfce98`:

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew -PbuildProfile=coreonly :core:test --offline --no-daemon
```

Exit 0. BUILD SUCCESSFUL in 1 minute 51 seconds. XML under `core/build/test-results/test`: tests=1276, failures=0, errors=0, skipped=0 (186 files, counted this session).

The run before `7cfce98` used the same command and exited 1. Gradle reported 1276 tests completed, 1 failed: `RetentionAndConfirmDefaultsTest.everyCommandCellTreatsConfirmAsOptIn` at the old `c.rollback().requireConfirm()` pin. After the pin update, that class alone exited 0 in about 13 seconds (4 tests). The full suite was then re-run, which is the XML above.

Targeted runs before their commits, all exit 0:

- WorldEdit messages, query compiler, parser, and the command-contract test: about 16 seconds. Full suite was not run at that commit.
- Lookup text, theme contrast, rollback wording, status, command contract, and the shared literal set: about 19 seconds.
- `BatchedAsyncWriteQueueTest` plus `AdmissionJournalTest`, including `rowTakenAsPauseLandsIsPutBackAndTheNextSubmitIsRejected`: about 49 seconds.

Brief baseline, not re-run on the base commit in this session: 1245 tests, 1 failure, 0 errors, 0 skipped. The failure was `AdmissionJournalTest.rejectedSubmitIsJournaledBeforeItIsCountedAsDropped`. That test passes in the 1276-test run.

Loader attempts, all failed before this mod's sources compiled:

- JDK 25, offline, `:mc-1.21.1:neoforge:compileJava`: `Unsupported class file major version 69` while configuring the common project.
- JDK 21, offline, same task: Fabric Loom 1.7.4 was not in the plugin cache. The 1.21.1 profile configures Fabric as well as NeoForge.
- JDK 21, online, same task: about 306 seconds, exit 1, inside `:mc-1.21.1:neoforge:createMinecraftArtifacts`, `error: release version 21 not supported`. This is the NeoForm recompile of Minecraft, using the JDK 21 `java` binary. It is not a compile of `GuardianCommands`.

## What I could not verify

- In-game chat: colours, line wrapping, click-to-copy, hover text, and `/vg tp` filled in from a lookup row. Click and hover are attached by reflection so one cell source can talk to both the old `ClickEvent` constructor and the newer record form. A mismatch drops the click and still sends the text. No client was launched.
- A real WorldEdit selection returning rows. Only the failure messages are unit-tested. WorldEdit is not on the core classpath.
- Boot logging of an open batch, and `/vg batch` against a live database, on a running server. JDBC behaviour is covered by the storage tests.
- That `/vg status` actually leaves the server tick. The handler submits the render and sends the lines back with `server.execute`. Not observed on a live tick.
- Any loader bytecode. Structural tests check that eight command files contain the required calls. They do not compile those files. 26.2 and 26.3 are outside that list.
- Daylight contrast on a bright background. The theme test measures mid-grey `#3C3C3C` only.

## Cells covered

| Source | Command and formatter sources updated | Compiled as a loader project | Mixins added | Logging gaps closed |
| --- | --- | --- | --- | --- |
| core | yes, and unit-tested | n/a (core test suite) | n/a | n/a |
| mc-1.18.2 Fabric | yes | no | no | no |
| mc-1.18.2 Forge | yes | no | no | no |
| mc-1.19.2 Fabric | yes | no | no | no |
| mc-1.19.2 Forge | yes | no | no | no |
| mc-1.20.1 Fabric | yes | no | no | no |
| mc-1.20.1 Forge | yes | no | no | no |
| mc-1.21.1 common (Fabric and NeoForge) | yes | no | no | no |
| mc-26.1.2 | yes | no | no | no |
| mc-26.2 | yes | no | no | no |
| mc-26.3 | yes | no | no | no |

The 1.21.1 NeoForge failure is the toolchain error in "Verification actually run". It does not clear or convict the shared sources.

## Risks

- Operators who learned that a bare purge only touched the world they were standing in will now delete matching rows in every world. The reply says `all worlds`. The age floors still apply. This is the behaviour chosen for item 4, and it is a wider delete than 2.2.1.
- Operators who used `t:1h-2h` expecting "everything older than 1 hour" will delete only the window. That matches the range, and it deletes less than 2.2.1 did.
- A failed rollback can leave the world partly changed and the batch open. `/vg batch close` does not repair the world. Someone still has to inspect with lookup before running rollback or restore again.
- Putting a paused row back uses a normal queue offer, so that one row can land behind a row that arrived during the race. The tested case is a one-slot queue, where it is the only row.
- Click and hover depend on reflection. On a client whose chat API matches neither shape, rows are plain text.
- `lookup.maxResultRows` changed at runtime still does not raise or lower the live query cap until restart. The status and reload text can imply that it did.
- `/vg undo` after a restart still has nothing to undo, even though the batch rows are in the database.
- Theme ids are near-white. On a bright screenshot without text shadow they can wash out. They were chosen so the dark chat box meets the contrast bar.
- Ten command files were edited in parallel. A missed hunk would show up as a compile error or a missing structural pin. The structural pin does not cover 26.2 or 26.3, and no cell was compiled.
