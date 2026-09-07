# VonixGuardian 2.1.0 common-generation repository

This repository is the single source tree for the VonixGuardian common-generation line. The common line starts at **2.0.0** and the stable line advances through **2.0.1** to the **2.1.0** release. The published `3.0.0-m1` milestone is a separate, explicitly limited prerelease over this tree; it remains historical provenance and is not relabeled by the stable 2.x line.

`2.0.0` and `2.0.1` are immutable stable releases on the common-generation line. `2.1.0` is their nine-cell stable successor. The published `3.0.0-m1` prerelease remains immutable, covers only its declared two-cell preview scope, and is not treated as a SemVer successor or replacement by this release. Existing historical releases, including `v1.0.0`, remain immutable.

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

- GitHub release automation: `.github/workflows/release.yml` builds all nine lanes on `v*` tags, creates stable releases only for tags without a prerelease suffix, and leaves hyphenated previews such as `v3.0.0-m1` for separate exact-candidate publication; it does not deploy, activate, restart, or migrate a server/database.
- Embedded project version for this stable release: **`2.1.0`** across all nine primary cells. The published `3.0.0-m1` prerelease remains a separate two-cell historical packet.
- Release validation: stable tag-triggered workflows build and package all nine lanes and publish SHA-256 checksums; preview releases use their explicitly accepted artifact scope and checksum packet.
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

The common-generation stable line is kept separate from the published `3.0.0-m1` milestone provenance so historical VonixGuardian releases and runtime metadata remain truthful. This `2.1.0` stable release is a parallel stable-line continuation from `2.0.1`, not a relabeling of `3.0.0-m1`. A stable major-version bump still requires a separate public API, configuration, persistence, and migration compatibility review.
