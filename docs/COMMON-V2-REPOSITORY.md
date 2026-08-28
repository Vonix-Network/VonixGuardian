# VonixGuardian 2.0.0 common-generation repository

This repository is the single source tree for the VonixGuardian common-generation line. The common line starts at **2.0.0** and is published as the prerelease label **`2.0.0-common.1`**.

`2.0.0-common.1` identifies the repository/layout generation. It does **not** rename the embedded project version: the accepted source currently builds as VonixGuardian **1.4.1**. Existing historical releases, including `v1.0.0`, remain immutable.

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

- GitHub release label: **`2.0.0-common.1`** (prerelease).
- Embedded project version: **1.4.1**.
- Static evidence: the accepted candidate passed the parent build/package matrix and source/artifact parity checks for the requested lanes, including schema/pair-ID compatibility tests.
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
