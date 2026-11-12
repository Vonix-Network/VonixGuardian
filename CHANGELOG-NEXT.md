# VonixGuardian — next release

## Summary

Command replies now say what happened, and the dangerous commands do what their flags claim. A purge marked as a preview or a count leaves the log in place. Changing one actions setting keeps the item and entity blacklists. Help text matches the commands, including the fact that undo changes the world again. Lookup, rollback, and status are written so the world, the count, and the outcome are visible on the first read.

## Breaking changes

- A purge with no world selector covers every world. The reply says `all worlds`, or the world you named. Limit a purge with `r:#nether`, `r:#overworld`, `r:#end`, or `r:#world_<name>`. Rollback and lookup still default to the world you are standing in.
- A window such as `t:1h-2h` deletes only rows inside that window. A single age such as `t:90d` still deletes rows older than that age. The safety check uses the newest row that would be deleted. A window that reaches into the last 30 days (in-game) or the last 24 hours (console) is refused even when the older end of the window is old enough.
- Lookup lines, rollback results, and the status report use new wording. A check that looked for `affected=` on a rollback line will not find it. Counts that are zero are left off the line. A clean rollback reads `Done — SUCCESS`. A result that needs repair reads `Needs repair — REPAIR_REQUIRED`.
- Chat colours are lighter. In the default aqua theme, block and item names are near-white so they stay readable on the dark chat box. On a bright scene they still rely on Minecraft's text shadow.
- `/vg help` lists every command. Undo is described as a second change to the world.

## Added

- `/vg batch`, `/vg batch show <id>`, and `/vg batch close <id>`, using the same permission as rollback. When a rollback did not finish, the next boot prints one line with the open batch ids. Show lists what that batch covered. Close marks the audit row finished and says the world was not changed. It does not replay the rollback.
- Purge preview prints the match count, up to eight sample rows (world, coordinates, block, player), and how many more match. Nothing is deleted.
- Lookup rows show the world, how many items moved, and a UTC clock time beside the relative time. Sign side, dye, and wax appear on the line when they were stored. `#verbose` adds the slot, the source, the paired row, the block state, and whether extra data was stored. The time copies the row id, the block name copies the full id, and the coordinates put `/vg tp` in the chat box. If that client's chat API does not accept the click, the text still appears.
- A command failure names the error. When the failure has no message, the reply uses the error type.

## Fixed

- `/vg purge t:90d #preview` and `/vg purge t:90d #count` deleted rows. They now report a count. Preview also shows a sample. The database is left alone.
- `/vg config set actions.entityBlockChangeCoalesceWindowMs <value>`, and the other single actions settings in that menu, saved a file with empty item and entity blacklists. Those lists stay as they were.
- `/vg help` described undo as dropping the last rollback from history, and it left out half the commands. Undo is described as changing the world again, and the list includes every command.
- `/vg purge t:90d` while standing in one world deleted only that world, and could report that zero rows were removed when the old rows were somewhere else. The reply now names the scope it used.
- `/vg purge t:1h-2h` deleted everything older than the newer end of the range. It now deletes only the window.
- After a rollback failed, the next boot logged one warning for every affected action, and the printed batch id had no command. Boot now logs one line, and `/vg batch` can show or close that batch. A failed batch stays open until someone closes it. Closing does not edit the world.
- `r:#worldedit` answered as if nothing had happened there when WorldEdit was missing, no selection existed, or the selection could not be read. The reply now says which of those happened.
- Rolling back several edits of the same block restored only the newest one and reported that nothing was skipped. The older rows are counted as skipped. The newest row is still the one applied.
- Lookup, rollback, and purge errors often ended in the word `null`. The reply now carries the message, or the error type when there is no message.
- Block and item names in the default theme were painted a dark cyan that disappears on a bright scene and is hard to read on mid-grey chat. They are near-white.
- While the audit writer was paused, a row could leave the queue at the same moment and a new row could take the freed slot. That row is put back, and the next submit is rejected and recorded.

## Changed

- Rollback `#count` is one count line and says nothing was changed. `#preview` says how many blocks would change and that the world was not changed. A real rollback says how many changed, then skipped, failed, compensated, and repair counts only when they are above zero, then whether the batch is closed.
- `#verbose` on a lookup adds the extra fields on the same line. The block name's hover text includes that detail even without the flag. Long names are shortened on screen. The click-to-copy value keeps the full id.
- `/vg status` opens with one sentence: storage type, whether the writer is running, and how many rows are waiting. The longer diagnostic blocks are still printed. Only the first section carries the mod name. The database read runs off the server thread, and the lines are sent back on the server thread.
- Confirm-before-rollback stays off unless you turn it on. The setting is still read before a rollback or restore changes the world.

## Performance

`/vg status` no longer reads the database on the server tick. Not measured. Nothing else was timed.

## Removed

No command and no config key was removed. `r:#worldedit` no longer answers with an empty result when the selection cannot be read.

## Verification

Core unit tests were run. No Minecraft server was started, so chat clicks, a live WorldEdit selection, and boot with an open batch were not exercised on a running server.

JDK 21, offline, after the last commit on this branch:

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew -PbuildProfile=coreonly :core:test --offline --no-daemon
```

The reports on disk from that run: 1276 tests, 0 failures, 0 errors, 0 skipped. Gradle reported BUILD SUCCESSFUL in 1 minute 51 seconds, exit 0.

The same command one commit earlier exited 1. It completed 1276 tests and failed 1: the confirm-before-rollback check still searched each command file for a constructor string that had moved into the shared config update. The behaviour was already opt-in. The test was pointed at the shared update and at the cell helper, with the reason written in the test. That class alone then passed (4 tests, exit 0, about 13 seconds). The 1276-test run above is after that correction.

Earlier targeted core runs, all exit 0: WorldEdit failure messages and the command-contract tests (about 16 seconds); lookup text, theme contrast, rollback wording, status, and the command contract (about 19 seconds); the write-queue tests, including the pause regression (about 49 seconds).

The brief's baseline on the starting commit was 1245 tests, 1 failure, 0 errors, 0 skipped. That failure was the paused-queue admission test. It passes in the 1276-test run above.

No loader project compiled. A 1.21.1 NeoForge compile on JDK 21 failed inside Minecraft's own recompile with `error: release version 21 not supported`, before this mod's sources were compiled. An offline attempt stopped because the Fabric plugin was not in the cache. JDK 25 stopped while configuring the project (`Unsupported class file major version 69`).
