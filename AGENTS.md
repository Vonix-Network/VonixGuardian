# AGENTS.md — VonixGuardian

## Repository identity

- **Repository:** `Vonix-Network/VonixGuardian`
- **Canonical checkout for this candidate:** `/root/work/vg-null-name-hotfix-20260903/source`
- **Default branch:** `main`
- **Project release:** **`2.0.1`** unreleased hotfix candidate
- **Common-generation lineage:** begins at `2.0.0`
- **Project role:** server-side audit, attribution, lookup, rollback, purge, and database utilities for modded Minecraft

This repository contains all supported Minecraft/loader lanes in one repository. It is independent of VSU and Viscord version numbering. Historical common-generation release `2.0.0` established the shared line; this unreleased hotfix candidate advances every lane to `2.0.1`.

## Read first

1. `AGENTS.md` (this file)
2. `README.md`
3. `CHANGELOG.md`
4. Root `gradle.properties`, `settings.gradle`, and `gradle/libs.versions.toml`
5. The selected `mc-<version>/` module's `build.gradle`
6. Relevant docs, tests, and `.github/workflows/release.yml`
7. Source and database/schema contracts before changing behavior

## Supported repository layout

| Minecraft | Loaders | Directory |
|---|---|---|
| 1.18.2 | Fabric, Forge | `mc-1.18.2/` |
| 1.19.2 | Fabric, Forge | `mc-1.19.2/` |
| 1.20.1 | Fabric, Forge | `mc-1.20.1/` |
| 1.21.1 | Fabric, NeoForge | `mc-1.21.1/` |
| 26.1.2 | NeoForge | `mc-26.1.2/` |

The repository-level `core/` module owns loader-neutral storage, audit, rollback, schema, API, and query contracts. Version modules consume that core through their settings/dependency wiring. The 26.1.2 NeoForge lane uses Java 25 and the ModDevGradle/Shadow/Jar-in-Jar packaging path described in `LIBRARY-PACKAGING.md`.

## Version contract

- Every Guardian lane uses the same embedded release version: **`2.0.1`** in this hotfix candidate.
- The intended release tag and GitHub release title are **`v2.0.1`**.
- Historical releases such as `v1.0.0`, `v1.4.1`, and `v1.4.3` remain immutable.
- Keep root `gradle.properties`, `GuardianAPI.PLUGIN_VERSION`, Fabric nested-core metadata, NeoForge metadata expansion, tests, README, docs, and changelog aligned.
- Do not add Minecraft or loader suffixes to the embedded public version; those belong in artifact names and the release matrix.

## Build and CI

The authoritative release workflow is `.github/workflows/release.yml`.

- `workflow_dispatch` runs the nine-lane build matrix only; the release job is guarded to `refs/tags/v*` and must not publish a release for a branch dispatch.
- Pushing `v2.0.1` runs all nine cells and publishes the GitHub release only after the matrix succeeds.
- 1.18.2–1.20.1 lanes use Java 17 with Gradle 8.10.2.
- 1.21.1 lanes use Java 21 with Gradle 8.10.2.
- 26.1.2 NeoForge uses Java 25 with Gradle 9.2.0. Gradle 8.x cannot load Java 25 bytecode and must not be used for that lane.
- CI provisions Gradle explicitly with `gradle/actions/setup-gradle`; do not assume wrapper binaries are committed.
- Each matrix job selects one release JAR and uploads it. The release job requires exactly nine JARs and writes `SHA256SUMS`.
- CI does not deploy, activate, restart, or migrate a live server/database.

Useful scoped commands, when the declared local toolchain is available:

```text
gradle -PbuildProfile=coreonly :core:build --no-daemon
gradle -PbuildProfile=mc1211 :mc-1.21.1:neoforge:build --no-daemon
gradle -PbuildProfile=mc2612 :mc-26.1.2:neoforge:build --no-daemon
```

Do not infer release readiness from a core-only build. The full nine-cell CI matrix is the publication gate.

## Runtime and database boundary

- Core schema truth is `core/src/main/java/network/vonix/guardian/core/storage/Schema.java`.
- Treat database migrations, lookup semantics, rollback safety, purge behavior, and pair-ID admission as correctness-sensitive.
- Static schema/tests and CI packaging do not prove a production database migration or live server activation.
- Do not connect to production databases, inspect live worlds, or change Pterodactyl/server state under a source publication task.
- Runtime testing, deployment, restart, and rollback execution require separate named owner authority and separate evidence.

## Packaging rules

- Preserve the loader-specific metadata and exact `2.0.1` expansion.
- Keep Fabric nested core JAR names synchronized with the core artifact version.
- Keep NeoForge 26.1.2 Jar-in-Jar handling and Shadow relocation rules intact; SQLite JNI must not be relocated in a way that breaks native symbol lookup.
- Exclude module descriptors and signature files only where the existing packaging contract requires it.
- The release selection must exclude sources/dev/slim/shadow artifacts.
- Every selected JAR must be archive-valid and contain the expected mod metadata, mod ID, version, loader identity, and required nested dependencies.

## Tests and verification

Before publication, verify:

- core unit and contract tests;
- all loader module compilation/package tasks in CI;
- schema version and pair-ID compatibility checks;
- archive metadata and nested-JAR contents;
- exact nine-artifact count and `SHA256SUMS`;
- remote tag/release commit/tree and uploaded asset digests after publication.

Static build/test evidence must be reported separately from runtime or production database evidence.

## Documentation rules

README/docs/changelog must accurately state:

- the five supported version directories and nine loader cells;
- Guardian's independent `2.0.0` release identity;
- common-generation origin at `2.0.0` without implying linkage to VSU or Viscord releases;
- database/schema and rollback safety boundaries;
- Java/Gradle requirements, including Gradle 9.2.0 for MC 26.1.2;
- CI release behavior and the fact that no live deployment or migration occurs.

Historical release text must remain historical and must not be rewritten as the current `2.0.0` feature set.

## Security and protected data

- Never read, print, commit, or transmit JDBC passwords, database URLs, API keys, tokens, private keys, webhook URLs, or authorization headers.
- Use documentation placeholders only.
- Do not add GitHub Actions secrets from this candidate.
- Treat world data, database files, runtime logs, player identities, and production configs as protected and out of scope unless separately authorized through an approved route.

## Git and change discipline

- Start with `git status --short --branch`.
- Preserve unrelated work, tags, releases, and sibling projects.
- Stage explicit paths; exclude `build/`, Gradle caches, runtime worlds, databases, logs, generated evidence, and credentials.
- Run `git diff --check` before commit.
- Never force-push or rewrite immutable release history.
- A green CI run is not live runtime proof; a GitHub release is not deployment or migration approval.

## Release procedure

1. Verify `main`, remote identity, current commit/tree, existing tags, and releases.
2. Confirm every lane embeds `2.0.1`.
3. Push the exact default-branch commit without force.
4. Run build-only CI and require 9/9 success.
5. Push only the exact `v2.0.1` tag.
6. Verify the tag-triggered release, nine JARs, `SHA256SUMS`, notes, and stable/prerelease state.
7. Independently read back the release commit/tree and every asset digest.
8. Keep live server/database activation explicitly unperformed unless separately evidenced and authorized.

## Stop conditions

Stop and report on:

- wrong repository/branch/tag identity;
- inconsistent version metadata or nested core JAR names;
- missing/failed matrix lane or artifact-count mismatch;
- Gradle/JDK mismatch for MC 26.1.2;
- schema/rollback/pair-ID test failure;
- protected data or credential exposure;
- request to deploy, restart, migrate, purge, or mutate a live server/database;
- request to overwrite a historical tag/release.

## Completion checklist

- [ ] Root AGENTS, README, docs, and changelog agree on Guardian `2.0.1`.
- [ ] All five version directories remain in this repository.
- [ ] All nine loader cells embed `2.0.1`.
- [ ] Manual CI dispatch cannot publish a release.
- [ ] CI build-only matrix passes 9/9.
- [ ] `v2.0.1` release assets and hashes read back remotely.
- [ ] No live deployment, restart, or production migration is claimed without evidence.
