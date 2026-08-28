# VonixGuardian 2.0.0 common-generation repository

This repository is the single source tree for the VonixGuardian common-generation line. The common line starts at **2.0.0** and is published as the stable release label **`2.0.0`**.

`2.0.0` is the embedded stable release version for every supported lane and identifies the first common-generation release line beginning at `2.0.0`. Existing historical releases, including `v1.0.0`, remain immutable.

## One repository, all supported Minecraft lanes

| Minecraft | Loaders | Java | Source directory |
|---|---|---:|---|
| 1.18.2 | Fabric, Forge | 17 | `mc-1.18.2/` |
| 1.19.2 | Fabric, Forge | 17 | `mc-1.19.2/` |
| 1.20.1 | Fabric, Forge | 17 | `mc-1.20.1/` |
| 1.21.1 | Fabric, NeoForge | 21 | `mc-1.21.1/` |
| 26.1.2 | NeoForge | 25 | `mc-26.1.2/` |

The root `core/` module contains the storage, queue, audit, query, rollback, schema, and test surface. Each `mc-<version>/` directory contains the shared target code plus its loader adapters. Minecraft versions remain together in one repository by design.

## Release status

- GitHub release automation: `.github/workflows/release.yml` builds all nine lanes on `v*` tags and creates a stable release after every matrix job passes; it does not deploy, activate, restart, or migrate a server/database.
- Embedded project version: **`2.0.0`** for every supported lane.
- CI gate: the tag-triggered workflow must provide fresh build/package evidence for this versioned successor; earlier R14 evidence does not cover the metadata/workflow changes.
- Live Minecraft activation, deployment, server restart, and production database migration were **not performed** for this source snapshot.
- Database configuration examples are documentation placeholders. Never commit real JDBC URLs, usernames, passwords, or connection strings.

## Building

Use the exact build profiles documented in the root README and [`docs/DEVELOPMENT.md`](DEVELOPMENT.md). The supported Java/toolchain split is intentional:

```bash
./gradlew -PbuildProfile=coreonly :core:test
./gradlew -PbuildProfile=mc1211 :mc-1.21.1:fabric:build :mc-1.21.1:neoforge:build
./gradlew -PbuildProfile=mc2612 :mc-26.1.2:neoforge:build
```

The 26.1.2 NeoForge lane requires Java 25. The 1.21.1 Fabric/NeoForge lane uses its compatible Gradle/Loom toolchain.

## Release naming

The common-generation label is kept separate from embedded project SemVer so historical VonixGuardian releases and runtime metadata remain truthful. A stable major-version bump requires a separate public API, configuration, persistence, and migration compatibility review.
