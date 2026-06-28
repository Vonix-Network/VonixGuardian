# Contributing to VonixGuardian

Thanks for your interest in making VonixGuardian better. This document covers how
to propose changes, how we expect contributions to be structured, and what we
will and will not accept.

## 1. Welcome

VonixGuardian welcomes contributions from anyone — bug reports, documentation
fixes, performance work, new features, additional mod adapters, and tests are
all valuable.

The project is licensed under the [MIT License](LICENSE). By submitting a
contribution you agree that your work will be distributed under the same
license.

All participation in this project — issues, pull requests, discussions, and
review comments — is governed by the
[Contributor Covenant Code of Conduct, version 2.1](https://www.contributor-covenant.org/version/2/1/code_of_conduct/).
Be respectful, be constructive, assume good faith.

## 2. Before you start

For anything non-trivial, **please open an issue before writing code**. This
avoids wasted effort on changes we may not be able to merge for design,
scope, or compatibility reasons. An issue is also the right place to discuss
API shape, persistence changes, or new mod integrations.

You can skip the issue step and go straight to a pull request for:

- Typo and grammar fixes in documentation.
- Small, obvious documentation improvements.
- A single, contained bug fix where the root cause is clear.

When in doubt, open an issue first — it's cheap, and it gives maintainers a
chance to point you at relevant context.

## 3. Development setup

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for full environment setup,
how to build the mod, how to run the Gradle test suite, and IDE configuration
notes (IntelliJ IDEA is the supported reference).

In short, you need a working JDK and a clone of this repository — the
Gradle wrapper handles everything else.

## 4. Branching

All work branches from `main`.

Name branches as `<type>/<short-desc>`, using the same `<type>` vocabulary as
Conventional Commits (see below). Examples:

- `fix/sqlite-lock-leak`
- `feat/worldedit-region-lookup`
- `docs/contributing-guide`
- `refactor/action-registry`
- `perf/rollback-batch-flush`

Keep the short description lowercase, hyphen-separated, and focused — the
branch name should make the PR's intent obvious at a glance.

## 5. Commits

VonixGuardian uses [Conventional Commits](https://www.conventionalcommits.org/).
Every commit subject must start with one of:

- `feat:` — a user-visible new feature or capability.
- `fix:` — a bug fix.
- `docs:` — documentation-only changes.
- `chore:` — build, tooling, or housekeeping with no runtime impact.
- `ci:` — CI configuration changes.
- `test:` — adding or improving tests with no behavior change.
- `refactor:` — internal restructuring with no behavior change.
- `perf:` — performance improvement with no behavior change.

For any non-trivial change, include a commit body that explains **why** the
change is being made, not just what it does. Reference the related issue
(`Refs #123` or `Fixes #123`) when applicable.

**Sign-off (DCO) is required.** Every commit must be signed off to certify
that you wrote the code (or otherwise have the right to submit it under
the project license). Use:

```
git commit -s -m "fix: release sqlite write lock on shutdown"
```

This appends a `Signed-off-by:` trailer using your configured Git identity.
PRs containing unsigned commits will be asked to amend before merge.

## 6. Code style

- **Java 17 baseline.** Core and the shared API target Java 17. The 1.21.1
  loader modules run on Java 21 and may use Java 21 features, but use them
  sparingly and only where they clearly help — code that could just as well
  be Java 17 should stay Java 17 so it can be lifted into shared modules
  later without rewrites.
- **4-space indentation**, no tabs.
- **Opening brace on the same line** (K&R / standard Java convention).
- **Javadoc for public APIs.** Anything in a `public` class or method that is
  part of the cross-module surface should have a Javadoc block describing
  contract, parameters, and any threading expectations.
- **No unnecessary abbreviations.** `connection` not `conn`, `transaction`
  not `txn`, `registry` not `reg`. Local loop variables (`i`, `e` in a
  `catch`) are fine.
- **Match surrounding style.** If a file already uses a particular pattern —
  naming, ordering, helper extraction — follow it. Style consistency inside
  a file beats global preference.

## 7. Tests

Every behavior change needs a test. Bug fixes need a regression test that
fails before the fix and passes after. New features need tests covering the
happy path and at least one failure mode.

The minimum bar for any PR is:

```
./gradlew :core:test
```

must pass locally and in CI.

**New `ActionType` implementations require a test that exercises both
`submit` and the `rollback` inverse**, asserting that applying then rolling
back returns the world (or persistence layer) to the original state. This is
non-negotiable — the rollback contract is what the whole mod is built on.

Pure refactors with no behavior change do not require new tests, but must
not reduce existing coverage.

## 8. Pull request process

1. Push your branch and open a pull request against `main`.
2. Fill out the PR template (see
   [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) once
   added) — describe the change, the motivation, and how you tested it.
3. CI must be green. Don't ask for review on a red PR unless you want help
   debugging the failure.
4. At least one maintainer review is required before merge.
5. PRs are merged with **squash-and-merge**, and the squash commit message
   must itself be a valid Conventional Commit. The maintainer merging will
   normally adjust the squash subject to match — keep your PR title in
   Conventional Commit form to make this easy.

Force-pushes to your branch during review are fine; just call them out if
they rewrite history reviewers have already commented on.

## 9. What we will NOT accept

These are hard rules. PRs violating them will be asked to change approach
before further review:

- **Hard imports of mod classes.** All optional mod integrations must use
  reflection (or the existing soft-dependency adapter pattern). A core or
  loader module must never `import` a class from WorldEdit, Create, Apotheosis,
  or any other third-party mod directly — even behind a `try/catch`. The JVM
  resolves imports at class load, which breaks servers without that mod
  installed.
- **Un-parameterized SQL.** All SQL must use `?`-bound `PreparedStatement`
  parameters. String concatenation into SQL — even for "trusted" values — will
  be rejected.
- **Per-mod-specific code in `core/`.** The `core/` module must remain
  universal and depend only on vanilla Minecraft interfaces (or our own
  abstractions over them). Mod-specific behavior belongs in its own adapter
  module behind a soft dependency.
- **Client-side rendering code.** VonixGuardian is a server-side mod. No
  rendering, no GUI screens, no client-only mixins. Chat-based UX only.

## 10. Reporting bugs

File bugs at
[GitHub Issues](https://github.com/Vonix-Network/VonixGuardian/issues). A
good bug report includes:

- **Minecraft version** (e.g. `1.21.1`).
- **Mod loader and version** (e.g. `NeoForge 21.1.x`, `Fabric 0.16.x`).
- **Full mod list**, or at minimum a list of mods VonixGuardian integrates
  with.
- **VonixGuardian version**.
- **A relevant log excerpt** — usually the stack trace plus the lines
  immediately around it. Full logs as a gist or attachment are even better.
- **Reproduction steps**, written so a maintainer can follow them on a
  fresh server.

Reports missing this information may be closed pending more detail.

## 11. Reporting security issues

**Do not open a public issue for a security vulnerability.** This includes
anything that could let a player bypass permission checks, corrupt the
rollback log, or escalate privileges on the server.

Follow the disclosure process in [SECURITY.md](SECURITY.md) instead.

## 12. Code of conduct

This project follows the
[Contributor Covenant version 2.1](https://www.contributor-covenant.org/version/2/1/code_of_conduct/).
Violations may be reported to the maintainers via the contact channel listed
in `SECURITY.md`.

## 13. Maintainers

VonixGuardian is maintained by the **Vonix Network team**.

Day-to-day decisions are made through pull request review. Major
architectural changes — new persistence backends, changes to the action
contract, breaking API changes, new top-level modules — must be discussed in
an issue first and reach rough consensus before implementation work begins.

## See also

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — environment setup, build, and
  IDE configuration.
- [SECURITY.md](SECURITY.md) — security policy and private disclosure
  process.
