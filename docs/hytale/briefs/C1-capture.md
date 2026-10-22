# C1 — Hytale capture cell (candidate worker brief)

You are a bounded candidate worker. Parent = Hermes (Venix), owns acceptance. You implement ONLY `hytale/src/main/java/network/vonix/guardian/hytale/capture/**` in `C:\Users\Pargon\DEV\vonixguardian-hytale`.

## Read first (in this order)
1. `docs/hytale/HYTALE-PORT-CONTRACT.md` — pin, verified API facts, core SPI, gates, output contract. It is authoritative.
2. `core/src/main/java/network/vonix/guardian/core/event/{EventGate,EventSubmitter,PreLogDispatcher}.java` and `core/src/main/java/network/vonix/guardian/core/action/{Action,ActionBuilder,ActionType}.java`.
3. `core/src/main/java/network/vonix/guardian/core/Guardian.java` lines ~1020-1110 (`submitBlockBreak`, `submitBlockPlace`, `submitContainerChange`, `submitEntityKill`, `submitExplosion`, …) — use these, do not construct `Action`s yourself where a helper exists.
4. Reference Minecraft lane: `mc-26.3/fabric/src/main/java/network/vonix/guardian/mc/v26_3/fabric/FabricEvents.java` and `mc-26.3/common/.../SourceTagger.java`.

## Mission
Feed the shared engine from Hytale events. Deliver:
- `HytaleBlockCapture` — player break/place (`BreakBlockEvent`, `PlaceBlockEvent`), recording actor UUID+name (from `PlayerRef` via `spi/PlayerDirectory`), world id via `spi/WorldBlockAccess.worldIdOf(...)`, position, prior block id + rotation (break) or new block id + rotation (place).
- `HytaleEnvironmentCapture` — `EnvironmentBreakBlockEvent` (fire/explosion/etc.) logged with the engine's environment actor convention used by the Minecraft lanes (find it: `Guardian.submitBlockBreak` callers with `UNKNOWN_ACTOR_NAME` / `Sentinel`).
- `HytaleDamageCapture` — `DamageBlockEvent` → attribution signal only if you can prove a player causer from the event; otherwise skip it and say so. Do NOT invent attribution.
- `CaptureRegistration` — a single `register(GuardianServices services, EventRegistry registry)` entry point the bootstrap will call from `setup()`.

## Rules (non-negotiable)
- Idempotent: the same event must never be logged twice; respect `Guardian.gate()` before submitting.
- Never swallow exceptions silently — log through the plugin logger passed in, count failures.
- No world mutation. No commands. No storage access beyond `Guardian` helpers.
- Never modify anything outside your package (except your result file).
- Verify every Hytale API signature you use against the real jar before relying on it:
  `"C:/Users/Pargon/AppData/Local/Programs/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/javap" -p -cp "C:/Users/Pargon/DEV/_hytale-deps/Server-0.7.4.jar" <fqcn>`
  Also useful: `docs/hytale-api/*.txt`.

## Gate you must run
```bash
cd /c/Users/Pargon/DEV/vonixguardian-hytale
bash scripts/hytale-compile-check.sh hytale/src/main/java/network/vonix/guardian/hytale/capture
```
`COMPILE_GATE=PASS` required. Do not run `gradle`/`gradlew` (parent-owned).

## Deliverable
Write `docs/hytale/results/capture.md` in the exact output-contract format from the contract (§6). Your `STATUS=CANDIDATE_COMPLETE` line is a claim; the parent re-runs the gate.
