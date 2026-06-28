# VonixGuardian — Development Guide

This guide walks contributors through setting up a local build environment,
navigating the multi-loader monorepo, and extending VonixGuardian without
breaking the shared contracts that the loader modules depend on.

If you are *using* VonixGuardian on a server, see [USAGE.md](../USAGE.md)
instead. This document is for people writing or modifying mod code.

---

## 1. Prerequisites

VonixGuardian targets five Minecraft versions across three mod loaders, which
means the toolchain has to straddle two Java generations.

| Tool | Version | Notes |
|------|---------|-------|
| JDK 21 | required | needed by `mc-1.21.1` (NeoForge + Fabric on MC 1.21.1 compile against Java 21) |
| JDK 17 | required | needed by `mc-1.18.2`, `mc-1.19.2`, `mc-1.20.1` (Forge & Fabric) and by `core/` |
| Gradle | bundled | use `./gradlew` — do **not** install Gradle system-wide; the wrapper pins the correct version |
| Git | any modern | tags drive the release workflow |
| IntelliJ IDEA Community | recommended | the project is laid out for IntelliJ's Gradle import. VS Code with the Gradle/Java extensions works, but ForgeGradle/NeoGradle run configurations are auto-generated for IntelliJ |

Both JDKs must be discoverable. The simplest layout is to install both with
your distro's package manager (or [SDKMAN](https://sdkman.io/)) and let Gradle's
toolchain provisioner pick the right one per module — `gradle.properties`
declares the toolchain version and Gradle will download a matching JDK
automatically if neither installed copy matches.

Verify your install:

```bash
java -version          # any JDK on PATH is fine; toolchains override per-module
./gradlew --version    # should print Gradle 8.x and the JVM picked up
```

---

## 2. Repository layout

VonixGuardian is a **monorepo** with one Gradle build rooted at the repo top.
For the full architecture diagram (core ↔ common ↔ loader), see
[README.md → Architecture](../README.md#architecture).

```
VonixGuardian/
├── core/                          # loader-agnostic logic: SQLite, EventGate,
│                                  #   RollbackEngine, Guardian facade, theme,
│                                  #   command parser. Pure Java 17, no MC deps.
├── mc-1.18.2/
│   ├── common/                    # shared loader bridge for this MC version
│   └── forge/                     # Forge entrypoint + event subscribers
├── mc-1.19.2/
│   ├── common/
│   └── forge/
├── mc-1.20.1/
│   ├── common/
│   ├── fabric/                    # Fabric Loom build
│   └── forge/                     # ForgeGradle build
├── mc-1.21.1/
│   ├── common/
│   ├── fabric/
│   └── neoforge/                  # NeoGradle build (Forge → NeoForge split at 1.21)
├── buildSrc/                      # convention plugins shared by every module
├── gradle/
│   └── libs.versions.toml         # single version catalog (loader versions, Mojang mappings, deps)
├── .github/workflows/             # build.yml + release.yml
├── docs/                          # this guide, ARCHITECTURE.md, etc.
├── settings.gradle                # enumerates every module
└── gradlew / gradlew.bat          # the wrapper — always use it
```

The two important invariants:

- **`core/` never imports Minecraft.** Anything that needs MC types lives in
  a `common/` or loader module.
- **Loader modules never reach into each other.** A 1.20.1 Forge change must
  not require touching 1.21.1 NeoForge code. Shared behavior belongs in `core/`
  (logic) or in the per-version `common/` (loader-bridge interfaces).

These rules are enforced by review, not by the build — please respect them.

---

## 3. Building

The default top-level build produces **8 jars**:

| Module | Output |
|--------|--------|
| `mc-1.18.2:forge` | `VonixGuardian-1.18.2-forge-<ver>.jar` |
| `mc-1.19.2:forge` | `VonixGuardian-1.19.2-forge-<ver>.jar` |
| `mc-1.20.1:forge` | `VonixGuardian-1.20.1-forge-<ver>.jar` |
| `mc-1.20.1:fabric` | `VonixGuardian-1.20.1-fabric-<ver>.jar` |
| `mc-1.21.1:fabric` | `VonixGuardian-1.21.1-fabric-<ver>.jar` |
| `mc-1.21.1:neoforge` | `VonixGuardian-1.21.1-neoforge-<ver>.jar` |
| `mc-1.19.2:fabric` | `VonixGuardian-1.19.2-fabric-<ver>.jar` |
| `mc-1.18.2:fabric` | `VonixGuardian-1.18.2-fabric-<ver>.jar` |

Each lands under `<module>/build/libs/`.

```bash
# Build everything (CI does this on every push)
./gradlew build

# Just the core library + its tests
./gradlew :core:test

# A single loader jar
./gradlew :mc-1.21.1:fabric:build
./gradlew :mc-1.20.1:forge:build

# Clean everything (rare — the wrapper caches loader toolchains)
./gradlew clean
```

The first build per loader is slow: ForgeGradle downloads MCP mappings,
Fabric Loom decompiles Minecraft for the dev workspace, and NeoGradle pulls
the NeoForm cache. Subsequent builds reuse `~/.gradle/caches` and complete
in seconds.

---

## 4. IDE setup

### IntelliJ IDEA

1. **Open** the repository root folder (do not "Import Project" — use **Open**
   and pick the directory containing `settings.gradle`).
2. IntelliJ detects the Gradle build and offers to import. Accept.
3. Wait for indexing. The Gradle tool window will show every module nested
   under the root.
4. Set the Project SDK to JDK 17. Per-module toolchains handle the JDK 21
   step for `mc-1.21.1` automatically.

The Gradle plugins each module uses:

| Loader / MC version | Plugin |
|---------------------|--------|
| Forge 1.18.2 / 1.19.2 / 1.20.1 | **ForgeGradle 5/6** |
| Fabric (all versions) | **Fabric Loom** |
| NeoForge 1.21.1 | **NeoGradle 7** |

All three plugins run via the wrapper — you should never need to install
them by hand.

### Forge dev-run setup

ForgeGradle requires a one-time IntelliJ run-config generation step:

```bash
./gradlew :mc-1.20.1:forge:genIntellijRuns
```

After it finishes, reload the Gradle project in IntelliJ. New run
configurations appear: `runClient`, `runServer`, `runData`. Pick
`runServer` to launch a dev server with the mod loaded.

### Fabric & NeoForge dev runs

Fabric Loom and NeoGradle produce their run configurations during the
initial Gradle sync — no extra `genIntellijRuns` step. Look for
`Minecraft Server` / `Minecraft Client` entries in the run-config dropdown.

---

## 5. Running a dev server

A dev server boots Minecraft from your IDE with the mod jar live-classloaded,
so edits → rebuild → restart cycles are fast.

### Forge (1.18.2 / 1.19.2 / 1.20.1)

```bash
./gradlew :mc-1.20.1:forge:genIntellijRuns    # once
# Then in IntelliJ: Run → "runServer"
# Or from the CLI:
./gradlew :mc-1.20.1:forge:runServer
```

The first launch will prompt you to accept the EULA — edit
`mc-1.20.1/forge/run/eula.txt` and set `eula=true`.

### Fabric (1.18.2 / 1.19.2 / 1.20.1 / 1.21.1)

```bash
./gradlew :mc-1.21.1:fabric:runServer
```

Loom auto-creates the `run/` directory. Same EULA prompt applies.

### NeoForge (1.21.1)

```bash
./gradlew :mc-1.21.1:neoforge:runServer
```

NeoGradle behaves like ForgeGradle here — accept the EULA in
`mc-1.21.1/neoforge/run/eula.txt`.

Once the server is up, the mod registers its commands (`/vg`, `/inspect`,
`/rollback`, etc.) and starts the SQLite WAL writer. Log into the dev world
with a client targeting the same MC version to exercise it.

---

## 6. Adding an `ActionType`

`ActionType` is the discriminator for every event VonixGuardian persists.
Adding a new value is a small but **load-bearing** change — please read
**[SHARED-CONTRACTS.md §2.1](../SHARED-CONTRACTS.md)** first. There are 39
types as of v1.0.0; each has a stable database id that must never be reused
or renumbered.

### Step-by-step

1. **Pick the next free DB id.** Append, never insert. The id is what the
   SQLite `events.action_type` column stores; renumbering would corrupt
   every existing world.
2. **Add the enum constant** in `core/.../ActionType.java` with that id and
   a `@since` Javadoc tag.
3. **Update `EventGate.shouldLog(...)`** if the new type needs sampling,
   throttling, or per-world filtering. Most new types just fall through to
   the default — only override if you have a reason.
4. **Add a submit method** on the `Guardian` facade (the public API) **and**
   on `EventSubmitter` (the internal queue writer). Keep the parameter
   order consistent with sibling methods of the same family.
5. **Implement the inverse** in `RollbackEngine`. Every loggable action must
   know how to undo itself — block placements have block-removal inverses,
   container deposits have container-withdraw inverses, etc. If the action
   is genuinely irreversible (e.g. a chat message), make it return
   `RollbackResult.NOT_REVERSIBLE` explicitly rather than throwing.
6. **Surface it in the UI.** Add the human-readable label to
   `core/.../theme/Strings.java` so `/inspect` and `/rollback` reports
   render correctly. Translation keys, not raw strings.
7. **Write unit tests** in `core/src/test/java/`. Cover at minimum:
   `EventGate.shouldLog`, `EventSubmitter` round-trip, `RollbackEngine`
   inverse, and theme rendering.
8. **Update CHANGELOG.md** under the unreleased section.

### Pitfall: enum extensions are registry writes

Extending `ActionType` is effectively a write to a global registry that
every loader module and every persisted world shares. If two contributors
add a new value in parallel and pick the same DB id, **you will silently
ship a database conflict**. The `delegating-parallel-builds` skill
documents this pitfall in detail — when you delegate enum additions to
parallel subagents, serialize the id allocation step or do it yourself
up-front.

---

## 7. Testing

```bash
./gradlew :core:test
```

`core/` has the JUnit 5 suite — **216 tests as of v1.0.0**. They cover the
event pipeline, rollback inverses, SQLite WAL writer, command parser, and
theme rendering. Run them before every PR; CI runs them on every push and
they must pass for the workflow to succeed.

**Loader-module tests are not wired yet.** Game-test harnesses for Forge,
Fabric, and NeoForge exist but require per-loader bootstrap that we have
deferred to **v1.1.0**. Until then, loader code is exercised manually via
dev servers (§5) and integration-tested via the CI build (jar produces
without classloader errors).

If you find yourself wanting a unit test for code in a `common/` or loader
module, push the testable logic down into `core/` if at all possible — that
is the structural reason `core/` exists.

---

## 8. Commit conventions

VonixGuardian uses **[Conventional Commits](https://www.conventionalcommits.org/)**:

```
feat:  add ActionType for villager profession changes
fix:   correct WAL flush race on server shutdown
docs:  document RollbackEngine inverse contract
chore: bump fabric-loader to 0.16.2
ci:    cache neoforge userdev between builds
```

Other accepted prefixes: `refactor:`, `test:`, `perf:`, `build:`, `style:`.

### PR titles

PR titles must **summarize the change** in imperative voice — the title is
what lands in the squash-merge commit and from there into `CHANGELOG.md`.
Avoid "WIP", "fixes", or pure ticket numbers. Bad: `fix bug`. Good:
`fix: prevent rollback double-apply on chunk reload`.

### Changelog

`CHANGELOG.md` follows **[Keep a Changelog](https://keepachangelog.com/)**.
When you open a PR, add an entry under the appropriate
`## [Unreleased]` subsection (`Added`, `Changed`, `Fixed`, `Removed`,
`Security`). The release workflow promotes `[Unreleased]` to a versioned
section at tag time.

---

## 9. Continuous Integration

Two GitHub Actions workflows live in `.github/workflows/`:

### `build.yml`

- **Triggers:** every `push` and every `pull_request` against `main`.
- **Job:** checkout → set up JDK 17 + 21 → `./gradlew build` → upload jars
  as workflow artifacts.
- **Required to pass** before any PR can merge.

### `release.yml`

- **Trigger:** push of a tag matching `v*` (e.g. `v1.0.0`, `v1.1.0-rc1`).
- **Job:** clean build of all **8 jars** → generate `SHA256SUMS` → create a
  GitHub Release with the tag → attach the jars and `SHA256SUMS` as
  release assets.
- The CHANGELOG section for the matching version is used as the release body.

To cut a release:

```bash
# 1. Bump version in gradle.properties (and any version refs)
# 2. Move [Unreleased] -> [x.y.z] in CHANGELOG.md and date it
# 3. Commit, then tag and push
git tag -a v1.1.0 -m "VonixGuardian 1.1.0"
git push origin v1.1.0
```

The `release.yml` workflow takes it from there. If the build fails, delete
the tag (`git tag -d v1.1.0 && git push --delete origin v1.1.0`), fix the
issue, and re-tag.

---

## See also

- [CONTRIBUTING.md](../CONTRIBUTING.md) — code of conduct, PR checklist,
  reviewer assignment.
- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — deeper dive on the core ↔
  common ↔ loader split, threading model, and SQLite schema.
- [SHARED-CONTRACTS.md](../SHARED-CONTRACTS.md) — the `ActionType` registry,
  event-payload shapes, and other cross-module invariants.
