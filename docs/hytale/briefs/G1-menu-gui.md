# BUILD — `/vg menu`: in-game Vonix Guardian GUI for Hytale

You are a bounded candidate worker. Parent = Hermes (Venix) owns acceptance. Implement a graphics menu for the
Guardian plugin, opened with `/vg menu`.

## Read first
1. `docs/hytale/HYTALE-PORT-CONTRACT.md` — pin, verified API facts, gates.
2. `hytale/src/main/java/network/vonix/guardian/hytale/spi/GuardianServices.java` — your only runtime handle.
3. `hytale/src/main/java/network/vonix/guardian/hytale/command/{CommandRegistration,GuardianCommand,CommandRuntime}.java` — how commands are registered, how a player sender is resolved, how messages are delivered, how the engine is invoked.
4. Existing command behaviour you must mirror in the menu: `status`, `lookup`, `inspect`, `rollback`, `restore`, `undo`, `purge`.

## Mission
Create `hytale/src/main/java/network/vonix/guardian/hytale/menu/**` and register `/vg menu` so a player gets a
real Hytale UI (not chat spam) that can drive the same operations the commands expose.

Requirements:
- `MenuRegistration.register(GuardianServices services, CommandRegistry registry)` adds a `menu` subcommand under the existing `/vg` tree (alias `/guardian`), plus a `MenuCommand` that opens the page for the sending player.
- Discover the real UI API from the jar — do not invent it. Start here:
  `"C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/jar" tf C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar | grep -iE "ui/|page|Page" | head -60`
  then `javap -p -cp <jar>` the candidates (`PageManager`, `CustomUIPage`/`InteractiveCustomUIPage`, `UIEventBuilder`, `BuilderTools` pages, `Page`). Use whatever the 0.7.4 jar actually exposes, and record the exact classes/signatures in your result.
- Minimum screens: **Status** (version/storage/queue/last rollback), **Lookup** (filter + scrollable result list), **Rollback** (radius/time pickers + preview → confirm), **Undo**, and a close/back path. Mutating actions must require an explicit confirm click, exactly like `--confirm` on the command line.
- Every engine call must stay off the world thread except world mutation, which must be hopped through `GuardianServices.mainThread()`. If the UI callback runs on the world thread, do not block on it.
- Permission gate: reuse `PlayerDirectory.hasPermission(uuid, node)`; fail closed when the check is unavailable. Read-only screens may be visible; mutating buttons disabled without permission.
- Close/refresh lifecycle: a page must not retain a player reference after close; no leaks on repeated open/close.

## Rules
- Write only inside `menu/` (plus your result file). Do NOT edit `core/`, `spi/`, `command/`, `capture/`, `world/`, `platform/`, `build.gradle`, `manifest.json`, or `settings.gradle` — the parent will wire `MenuRegistration` into the bootstrap.
- Do not run `gradle`/`gradlew`. Per-cell gate:
  `bash scripts/hytale-compile-check.sh hytale/src/main/java/network/vonix/guardian/hytale/menu`
  must print `COMPILE_GATE=PASS`.
- If the UI API in 0.7.4 cannot do what the brief asks (e.g. no interactable page), stop and report `PREMISES=` with the real API surface and the closest achievable design — do not fake a menu with chat output.

## Deliverable
`docs/hytale/results/menu.md` in the contract's §6 output format, including: exact UI classes/signatures used, the screen/action matrix, the thread each callback runs on, and anything left unproven without a live client.

Budget: up to 90 turns. Write your result file early and update it; a partial but on-disk result beats a perfect one that never lands.
