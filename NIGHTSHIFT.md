# NIGHTSHIFT — v1.2.5 Perfection Wave

Status: **ACTIVE** — WeedMeister explicitly called out remaining parity/quality gaps after the initial v1.2.5 patch.
Started: 2026-07-02
Repo: `/root/DEV/VonixGuardian`
Base HEAD: `bf97c33 fix(vg): harden 1.2.4 safety paths`
Current working tree: dirty; contains partial v1.2.5 changes for rollback expansion, coalescer cap telemetry, and initial Fabric world-event mixins.

## Goal

Make VonixGuardian as close to "100% perfect" as is realistically verifiable for the next release: CoreProtect/Ledger-class fidelity, safe modded-server behavior, bounded rollback memory, aligned attribution policy, and real loader/runtime smoke coverage instead of compile-only confidence.

## Non-negotiables

- CoreProtect source at `/root/staging/coreprotect-ref/CoreProtect` is ground truth for CP behavior claims.
- Ledger source at `/root/staging/ledger-ref/Ledger` is secondary reference for modded/server ergonomics.
- Cells stay thin; reusable logic belongs in `core/`.
- Live player accounts are never mutated for testing.
- Fabric / Forge / NeoForge parity claims require either GameTest/smoke proof or a documented blocker.
- Large rollback must not materialize unbounded actions into memory without an explicit configured cap/progress/cancel path.
- Modded entity attribution must be policy-aligned across Fabric and Forge/NeoForge; loader differences are implementation details, not behavior excuses.

## Active lanes

| Lane | Priority | Scope | Deliverable | Status |
|---|---:|---|---|---|
| W6-A | P0 | Fabric fire/fade/spread/leaves/dispense/form mixins | Actual implemented mixins across 1.18.2/1.19.2/1.20.1/1.21.1, not just registered names; compile + targeted runtime feasibility notes | In progress |
| W6-B | P0 | Loader smoke / GameTest | A runnable smoke or GameTest harness proving boot + representative event producers per loader/version where feasible | Pending |
| W6-C | P0 | Large rollback memory safety | Configurable cap and/or streaming/progress/cancel design with regression tests; no silent unbounded materialization | Pending |
| W6-D | P0 | Modded entity attribution alignment | Unified policy matrix + code changes so Fabric and Forge/NeoForge use equivalent attribution decisions | Pending |
| W6-E | P1 | Explosion fidelity | Per-block old-state fidelity or explicit audit-only classification with rollback safety; compare Forge/Fabric paths | Pending |
| W6-F | P1 | Container fidelity | Beyond chest-only Fabric snapshots: barrel/shulker/hopper and stale snapshot cleanup/caps/timeouts/status | Pending |
| W6-G | P1 | Hanging/entity interaction fidelity | Player/non-player hanging place/break and entity-interaction parity across loaders | Pending |
| W6-H | P1 | More-gap adversarial audit | CoreProtect/Ledger comparison for anything not already in lanes A-G: commands, config, API, permissions, query UX, rollback semantics, event coverage | Pending |

## Current checkpoint

Already verified before this wave:

```bash
./gradlew -PbuildProfile=coreonly :core:build
./gradlew -PbuildProfile=forgeonly :mc-1.18.2:forge:compileJava :mc-1.19.2:forge:compileJava :mc-1.20.1:forge:compileJava :mc-1.21.1:neoforge:compileJava -x test
./gradlew -PbuildProfile=mc1182 :mc-1.18.2:fabric:compileJava -x test
./gradlew -PbuildProfile=mc1192 :mc-1.19.2:fabric:compileJava -x test
./gradlew -PbuildProfile=mc1201 :mc-1.20.1:fabric:compileJava -x test
./gradlew -PbuildProfile=mc1211 :mc-1.21.1:fabric:compileJava :mc-1.21.1:neoforge:compileJava -x test
```

All passed, but this is compile-only confidence. It does **not** prove runtime mixin application, GameTest coverage, or full parity.

## Dispatch contract for audit subagents

Subagents for this wave are read-only unless explicitly assigned an isolated worktree. If reading the dirty parent tree, they must not edit files. Every finding must include file:line evidence and a concrete fix recommendation. Use severity buckets:

- CRITICAL — unsafe data loss/world mutation/crash/boot failure
- HIGH — parity gap or feature advertised but not actually working
- MEDIUM — fidelity/performance/observability gap
- LOW — polish/docs/test-only improvement

Each report should include `## Verified clean` so coverage is visible, not just bugs.

## Integration order

1. Finish Fabric world-event mixin actual implementation and registration.
2. Add tests/smoke harness scaffolding so later lanes can prove runtime behavior.
3. Land rollback memory cap/progress/cancel in core with tests.
4. Align attribution policy and then retest event lanes against it.
5. Deep fidelity passes: explosion, containers, hanging, interaction.
6. Run adversarial gap audit, fix CRITICAL/HIGH, defer remaining MEDIUM/LOW explicitly.
7. Final local build matrix + jar build where feasible; only then release/tag/deploy.
