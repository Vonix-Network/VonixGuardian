# AGENTS.md — VonixGuardian

## Repository identity

- **Repository:** `Vonix-Network/VonixGuardian`
- **Default branch:** `main`
- **Project release:** **`2.2.0`** (`mod_version` in `gradle.properties`)
- **Schema:** **9** (`Schema.CURRENT_VERSION`)
- **Primary jars:** **13**
- **Project role:** server-side audit, attribution, lookup, rollback, purge, and database utilities for modded Minecraft

This repository contains every supported Minecraft/loader lane. It is independent of VSU and Viscord version numbering. The published `3.0.0-m1` preview remains separate historical provenance and is not relabeled by `2.2.0`. Historical `2.1.x` changelog sections stay historical.

## Read first

1. `AGENTS.md` (this file)
2. `README.md`
3. `CHANGELOG.md` section `[2.2.0]`
4. Root `gradle.properties`, `settings.gradle`, and `.github/workflows/release.yml`
5. The selected `mc-<version>/` module's `build.gradle`
6. `core/src/main/java/network/vonix/guardian/core/storage/Schema.java` before any storage change

## Supported matrix

| Minecraft | Loaders | Directory | Release profile | JDK | Gradle |
|---|---|---|---|---|---|
| 1.18.2 | Fabric, Forge | `mc-1.18.2/` | `mc11xx` | 17 | 8.10.2 |
| 1.19.2 | Fabric, Forge | `mc-1.19.2/` | `mc11xx` | 17 | 8.10.2 |
| 1.20.1 | Fabric, Forge | `mc-1.20.1/` | `mc11xx` | 17 | 8.10.2 |
| 1.21.1 | Fabric, NeoForge | `mc-1.21.1/` | `mc1211` | 21 | 8.10.2 |
| 26.1.2 | NeoForge | `mc-26.1.2/` | `mc2612` | 25 | 9.2.0 |
| 26.2 | NeoForge | `mc-26.2/` | `mc262` | 25 | 9.2.0 |
| 26.2 | Fabric | `mc-26.2/` | `mc262fabric` | 25 | 9.6.0 |
| 26.3 | NeoForge | `mc-26.3/` | `mc263` | 25 | 9.2.0 |
| 26.3 | Fabric | `mc-26.3/` | `mc263fabric` | 25 | 9.6.0 |

That is thirteen primary jars. Every jar embeds root `mod_version` `2.2.0`. Do not add a per-cell `-beta` suffix.

The 26.3 NeoForge **loader pin** is `26.3.0.6-beta` (`mc-26.3/neoforge/build.gradle`). That pin is the NeoForge version the cell compiles against. The published jar name is `vonixguardian-neoforge-26.3-2.2.0.jar`.

Fabric 26.2 is included only under `mc262fabric`. Fabric 26.3 is included only under `mc263fabric`. Loom 1.17.21 requires Gradle 9.5+, so those Fabric cells must not be configured by `mc262`, `mc263`, or `all` (those profiles run on Gradle 9.2.0 or 8.10.2). Do not bump the root 8.10.2 wrapper to satisfy one cell.

`core/` is loader-neutral storage, audit, rollback, schema, API, and query code. Version modules depend on it. There is no Architectury runtime.

## Version contract

- `2.2.0` is the embedded public version on every current cell.
- The historical Milestone 1 preview artifact embeds `3.0.0-m1`. Leave that provenance immutable.
- Historical tags such as `v1.0.0`, `v1.4.1`, `v1.4.3`, and `v2.1.0` stay immutable.
- Do not add Minecraft or loader suffixes to the embedded public version. Those belong in artifact names.
- Do not bump `mod_version` or `Schema.CURRENT_VERSION` as a drive-by.

## Build and CI

The authoritative release workflow is `.github/workflows/release.yml`.

- `workflow_dispatch` builds the matrix and does not publish.
- A `v*` tag without a hyphen runs the release job. `prerelease` stays false.
- The release job requires exactly **13** jars and writes `SHA256SUMS`.
- Release notes are extracted from the `## [2.2.0]` changelog section. Do not hardcode an older release's highlights.
- NeoForge 26.2 and 26.3 jobs stay on Gradle 9.2.0. Fabric 26.2 and 26.3 jobs stay on Gradle 9.6.0.
- Gradle 8.x cannot load Java 25 bytecode.
- CI does not deploy, restart, or migrate a live server or database.

Useful scoped commands:

```text
gradle -PbuildProfile=coreonly :core:test --no-daemon
gradle -PbuildProfile=mc11xx :mc-1.18.2:forge:build --no-daemon
gradle -PbuildProfile=mc1211 :mc-1.21.1:neoforge:build --no-daemon
gradle -PbuildProfile=mc2612 :mc-26.1.2:neoforge:build --no-daemon
gradle -PbuildProfile=mc262 :mc-26.2:neoforge:build --no-daemon
gradle -PbuildProfile=mc263 :mc-26.3:neoforge:build --no-daemon
gradle -PbuildProfile=mc262fabric :mc-26.2:fabric:build --no-daemon
gradle -PbuildProfile=mc263fabric :mc-26.3:fabric:build --no-daemon
```

A green core test run is not a dedicated-server boot. A green loader build is not live rollback proof.

## Runtime and database boundary

- Schema truth is `Schema.java`. Current version is 9. Migrations are additive.
- Do not change stored data formats, hash width, or salt handling without an explicit schema decision. `IpHasher` stores `sha256:` plus a 16-hex prefix. `privacy.hashIps` defaults to false, so the raw address is what gets stored.
- `purge.autoPurgeSeconds` ships as `GuardianConfig.DEFAULT_AUTO_PURGE_SECONDS` (`0`, unbounded). `7776000` is the suggested busy-server horizon and is not applied automatically.
- `rollback.requireConfirm` defaults to false. Do not change the op-level table to add a confirmation.
- Boot logs open rollback batches. It does not replay them.
- Static tests and CI packaging do not prove a production migration or a live chest rollback.
- Do not connect to production databases, inspect live worlds, or change server state under a source task.

## Packaging rules

- Preserve loader-specific metadata. Every current artifact expands `2.2.0`.
- Keep Fabric nested core JAR names synchronized with the core artifact version.
- Keep NeoForge Jar-in-Jar and Shadow relocation rules intact. SQLite JNI must not be relocated in a way that breaks native symbol lookup.
- The release selection excludes sources, dev, slim, and shadow artifacts.
- Every selected JAR must be a valid archive with the expected mod id, version, and loader identity.

## Tests and verification

Before treating a tree as a release candidate, verify:

- core unit and contract tests, including the block-entity clear/place contract and the container preflight;
- all thirteen loader cells compile and package;
- schema version still 9;
- artifact count is 13 and `SHA256SUMS` matches those jars.

Report static build evidence separately from dedicated-server or production-database evidence.

## Documentation rules

README, docs, changelog, and release notes must agree on:

- release `2.2.0` and schema 9;
- the thirteen-jar matrix above;
- the 26.3 NeoForge loader pin `26.3.0.6-beta`, without calling the jar a prerelease;
- `privacy.hashIps` default **false**, and a hard validation error (not a warning) when hashing is enabled with the placeholder salt;
- `autoPurgeSeconds` `0` means unbounded, with `7776000` as the suggested busy-server horizon;
- open rollback batches are inspected, not auto-replayed.

Do not rewrite historical `2.1.x` changelog sections into the current feature set.

## Security and protected data

- Never read, print, commit, or transmit JDBC passwords, database URLs, API keys, tokens, private keys, webhook URLs, or authorization headers.
- Use documentation placeholders only.
- Treat world data, database files, runtime logs, player identities, and production configs as protected.

## Git and change discipline

- Start with `git status --short --branch`.
- Stage explicit paths. Exclude `build/`, `.gates/`, Gradle caches, jars, logs, and credentials.
- Never force-push or rewrite immutable release history.
- Do not push, tag, or publish unless the owner asked for that exact action.
- A green CI run is not live runtime proof.

## Stop conditions

Stop and report on:

- a request to deploy, restart, migrate, purge, or mutate a live server or database;
- a request to overwrite a historical tag or release;
- a schema or stored-format change that the task did not explicitly authorize;
- a matrix lane that fails, or an artifact count other than 13;
- a Gradle/JDK mismatch for a 26.x cell.
