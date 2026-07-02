# Contributing to VonixGuardian

Thanks for wanting to help. VonixGuardian is a CoreProtect-parity audit and
rollback tool for modded Minecraft (Fabric / Forge / NeoForge, MC 1.18.2 →
1.21.1). This document is a *lightweight* contributor guide — deep design
docs live in `docs/`.

## Ways to help

1. **File bugs** — reproducible issues with logs and a minimal modpack description.
2. **CoreProtect parity gaps** — if `/co X` behaves one way and `/vg X` behaves differently, that is a bug. Reference: `docs/COREPROTECT-COMPARISON.md`.
3. **Language bundles** — see `core/src/main/resources/lang/`. Contribute a new locale by copying `en_us.properties` and translating values (never keys).
4. **Loader-specific event coverage** — mixin implementations that fill event gaps on Fabric or NeoForge. See open issues tagged `event-coverage`.
5. **API consumers / integrations** — third-party mods that consume `network.vonix.guardian:core`. Reach out via Discord before shipping so we can add you to `docs/PLUGINS.md`.

## Development workflow

```bash
git clone https://github.com/Vonix-Network/VonixGuardian
cd VonixGuardian
./gradlew -PbuildProfile=coreonly :core:build         # ~5-10s, run this often
./gradlew -PbuildProfile=coreonly :core:test          # 40+ test classes
./gradlew -PbuildProfile=forgeonly :mc-1.20.1:forge:build -x test   # single Forge cell
```

Fabric-loom has a known cross-version classloader bug with Gradle 8.10+; use
the `forgeonly` profile for aggregate builds and let CI cover Fabric cells.

## Coding standards

- **Java 17 for MC 1.18–1.20 cells, Java 21 for 1.21+**
- Public core API lives under `network.vonix.guardian.core.api`
- Cell code stays thin: event subscriptions, brigadier command tree, MC-version-specific extractors. Anything else belongs in `core/`.
- Add tests. Every non-trivial change to `core/` needs at least one unit test.
- Follow Keep-a-Changelog format in `CHANGELOG.md` under `## [Unreleased]`.

## Commit + PR conventions

- Signed commits welcome, not required.
- Squash-merge is default. Keep the PR title in Conventional Commits shape (`feat:`, `fix:`, `docs:`, `chore:`, `test:`).
- One logical change per PR. Big waves get broken into feature branches by area.

## Sensitive topics

- **Do not commit secrets, tokens, or personal data.** The repo has a strict `.gitignore` for local databases, keys, and logs.
- **CoreProtect source is a reference, not a target for copying.** Read `/root/staging/coreprotect-ref/CoreProtect` (if you have local access) or CoreProtect's public repo for parity questions, but do not copy code verbatim — CP is `LGPL-3.0-or-later`, VG is MIT, and mixing licenses is a legal problem. Match behaviour, write our own implementation.
- **Vulnerabilities** — see `SECURITY.md`. Do not file security bugs as public issues.

## Contact

- GitHub: https://github.com/Vonix-Network/VonixGuardian
- Discord: https://vonix.network/discord (see the `#guardian-dev` channel)

## License

By contributing, you agree that your contributions are licensed under the
project's MIT license.
