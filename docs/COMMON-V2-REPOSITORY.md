# VonixGuardian common-generation repository

This repository is the single source tree for the VonixGuardian common-generation line. The common line starts at **2.0.0**. The current release in this tree is **2.2.0** (thirteen primary jars, schema 9). The published `3.0.0-m1` milestone is a separate, explicitly limited preview over this tree; it remains historical provenance and is not relabeled by 2.2.0.

`2.0.0` and `2.0.1` are immutable releases on the common-generation line. `2.1.0` is their historical nine-cell successor. The published `3.0.0-m1` preview remains immutable, covers only its declared two-cell preview scope, and is not treated as a SemVer successor or replacement by 2.2.0. Existing historical releases, including `v1.0.0`, remain immutable.

## One repository, all supported Minecraft lanes

| Minecraft | Loaders | Java | Source directory |
|---|---|---:|---|
| 1.18.2 | Fabric, Forge | 17 | `mc-1.18.2/` |
| 1.19.2 | Fabric, Forge | 17 | `mc-1.19.2/` |
| 1.20.1 | Fabric, Forge | 17 | `mc-1.20.1/` |
| 1.21.1 | Fabric, NeoForge | 21 | `mc-1.21.1/` |
| 26.1.2 | NeoForge | 25 | `mc-26.1.2/` |
| 26.2 | NeoForge | 25 | `mc-26.2/` |
| 26.3 | NeoForge (loader pin `26.3.0.6-beta`), Fabric | 25 | `mc-26.3/` |
| 26.2 | Fabric | 25 | `mc-26.2/` |

The root `core/` module contains the storage, queue, audit, query, rollback, schema, and test surface. Each `mc-<version>/` directory contains the shared target code plus its loader adapters. Minecraft versions remain together in one repository by design.

## Release status

- GitHub release automation: `.github/workflows/release.yml` builds the thirteen-cell matrix on `v*` tags, publishes only for tags without a hyphen, and leaves hyphenated previews such as `v3.0.0-m1` for separate publication. It does not deploy, restart, or migrate a server or database.
- Embedded project version for this release: **`2.2.0`** across all thirteen primary jars. The published `3.0.0-m1` preview remains a separate two-cell historical packet.
- Release validation: tag-triggered workflows build and package all thirteen cells and publish SHA-256 checksums. The release job requires exactly 13 jars.
- Installation: choose the artifact matching your Minecraft version, loader, and Java environment, then follow [`docs/INSTALL.md`](INSTALL.md).
- Live Minecraft activation, deployment, server restart, and production database migration were **not performed** for this source snapshot.
- Database configuration examples are documentation placeholders. Never commit real JDBC URLs, usernames, passwords, or connection strings.

## Building

Use the exact build profiles documented in the root README and [`docs/DEVELOPMENT.md`](DEVELOPMENT.md). The supported Java/toolchain split is intentional:

```bash
./gradlew -PbuildProfile=coreonly :core:test
./gradlew -PbuildProfile=mc1211 :mc-1.21.1:fabric:build :mc-1.21.1:neoforge:build
./gradlew -PbuildProfile=mc2612 :mc-26.1.2:neoforge:build
./gradlew -PbuildProfile=mc262 :mc-26.2:neoforge:build
```

The 26.1.2 and 26.2 NeoForge lanes require Java 25 and Gradle 9.2.0+. The 1.21.1 Fabric/NeoForge lane uses its compatible Gradle/Loom toolchain.
gradle -PbuildProfile=mc263 :mc-26.3:neoforge:build
NeoForge 26.1.2, 26.2, and 26.3 require Java 25 and Gradle 9.2.0. The 26.3 NeoForge loader pin is `26.3.0.6-beta`; the jar version is still `2.2.0` and the cell is one of the thirteen release jars. Fabric 26.2 and 26.3 require Java 25 and Gradle 9.6.0 (`mc262fabric`, `mc263fabric`) and are not configured by the NeoForge profiles.
gradle -PbuildProfile=mc262fabric :mc-26.2:fabric:build
The 26.1.2 NeoForge lane requires Java 25. The 26.2 Fabric lane requires Java 25, Gradle 9.6.0+, and Loom 1.17. The 1.21.1 Fabric/NeoForge lane uses its compatible Gradle/Loom toolchain.

## Release naming

The common-generation line is kept separate from the published `3.0.0-m1` milestone provenance so historical VonixGuardian releases and runtime metadata remain truthful. `2.2.0` continues that line. It is not a relabeling of `3.0.0-m1`. A major-version bump still requires a separate public API, configuration, persistence, and migration compatibility review.
