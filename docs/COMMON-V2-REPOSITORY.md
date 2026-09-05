# VonixGuardian 2.0.1 common-generation repository

This repository is the single source tree for the VonixGuardian common-generation line. The common line starts at **2.0.0**. The current `3.0.0-m1` Milestone 1 preview is a separate from-scratch CoreProtect/Ledger parity milestone over this repository and is limited to the accepted 1.21.1 Fabric stateful-container slice.

`2.0.0` was the embedded stable release version for every supported lane in the historical common-generation release and identifies the first common-generation release line beginning at `2.0.0`. The earlier `2.0.1` hotfix candidate remains preserved as superseded provenance; existing historical releases, including `v1.0.0`, remain immutable. The `3.0.0-m1` preview does not claim full nine-cell closure or complete CoreProtect v24 parity.

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
- Embedded project version: **`3.0.0-m1`** for the selected Milestone 1 preview artifact; other lanes are not part of this preview release packet.
- Release validation: the tag-triggered workflow builds and packages all nine lanes and publishes SHA-256 checksums with each tagged release.
- Installation: choose the artifact matching your Minecraft version, loader, and Java environment, then follow [`docs/INSTALL.md`](INSTALL.md).
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
