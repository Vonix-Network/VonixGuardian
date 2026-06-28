# Security Policy

VonixGuardian takes security seriously. This document describes how to report
vulnerabilities, what versions receive fixes, and how disclosure is handled.

> **Note:** This is the **public** security policy. It is separate from the
> internal `SECURITY-AUDIT.md` red-team findings document, which is maintained
> for development reference only.

---

## 1. Supported Versions

| Version  | Status                                  | Security Patches            |
| -------- | --------------------------------------- | --------------------------- |
| `v1.0.x` | **Supported** (current stable)          | Yes — all severities        |
| `v0.x`   | Unsupported (internal pre-release)      | No                          |

### Future support window

When `v1.1.0` ships, the support matrix will roll forward:

- `v1.1.x` will become the primary supported branch and receive patches for
  all severities.
- `v1.0.x` will enter a **6-month critical-only** maintenance window measured
  from the `v1.1.0` release date. During that window only **critical** issues
  (RCE, auth bypass, mass data exposure) will be backported.
- After the 6-month window ends, `v1.0.x` is end-of-life and receives no
  further patches.

Users on unsupported versions are strongly encouraged to upgrade before
reporting issues — fixes will only be shipped on the supported branch(es).

---

## 2. Reporting a Vulnerability

**Please do not open a public GitHub issue for security reports.** Public
issues alert attackers before a fix can be shipped and put every server
running VonixGuardian at risk.

Use one of the following private channels:

1. **Preferred — GitHub Security Advisory:**
   <https://github.com/Vonix-Network/VonixGuardian/security/advisories/new>
2. **Email:** `security@vonix.network`

> The `security@vonix.network` mailbox is a placeholder while the maintainers
> finish setting up the alias. Until it is confirmed live, please use the
> GitHub Security Advisory link above. If you email and do not get an
> autoreply within an hour, fall back to the advisory form.

### What to include in your report

A good report dramatically shortens triage time. Please include:

- **VonixGuardian version** (e.g. `v1.0.0`)
- **Minecraft version** (e.g. `1.20.1`)
- **Mod loader** (Forge / NeoForge / Fabric) and loader version
- **Reproduction steps** — exact commands, config, or event sequence
- **Impact assessment** — what an attacker can do with this bug
- **Suggested fix** if you have one (optional, but appreciated)
- **Whether you want to be credited** in the release notes (see §6)

PoC code, logs, and minimal repro mods can be attached to the advisory or
sent as encrypted attachments to the email address above.

---

## 3. Response SLA

Once a report reaches us via one of the private channels above:

| Phase             | Target                                   |
| ----------------- | ---------------------------------------- |
| Initial response  | **Within 72 hours** of receipt           |
| Triage complete   | **Within 7 days** (severity + scope set) |
| Fix — critical    | **Within 14 days** of triage             |
| Fix — high        | **Within 30 days** of triage             |
| Fix — medium      | **Within 60 days** of triage             |
| Fix — low         | **Next scheduled release**               |

Severity follows CVSS-style reasoning: critical = unauthenticated RCE or auth
bypass, high = privilege escalation or unauthorized data access, medium =
authenticated data tampering or DoS, low = information disclosure with no
direct impact.

If we cannot meet a deadline (complex root cause, upstream dependency, etc.)
we will tell the reporter and agree on a revised timeline rather than go
silent.

---

## 4. Disclosure Timeline

VonixGuardian prefers **coordinated disclosure**:

1. Reporter sends report privately.
2. Maintainers confirm, triage, and develop a fix.
3. Fix is shipped in a patched release.
4. Reporter and maintainers agree on a public disclosure date — typically
   shortly after the patched release so users have time to upgrade.
5. A security advisory is published on GitHub and listed in §7 below.

If no agreement on a disclosure date is reached, a **default 90-day embargo**
applies from the date of the initial report. After 90 days the reporter is
free to disclose publicly even if no fix has shipped — though we will do
everything reasonable to avoid that outcome.

---

## 5. Scope

### In scope

- SQL injection in any persistence layer query
- Authentication or authorization bypass (e.g. running protected `/vg`
  subcommands without the required permission)
- Unauthorized data access (reading other players' records, audit log
  tampering, etc.)
- Remote code execution reachable through mod interaction, network events,
  or crafted Minecraft packets handled by VG
- Denial of service triggered by crafted in-game events, packets, or config
  that a non-op player can cause

### Out of scope

- Bugs that require **op permission** to exploit. Operators already have
  full administrative access by design; "an op can break things" is not a
  vulnerability.
- Bugs in **third-party dependencies** (Forge, NeoForge, Fabric, JDBC
  drivers, etc.) — please report those upstream. We will track and update
  once upstream ships a fix.
- Denial of service via **legitimate-but-expensive queries** (e.g. running
  `/vg purge` over an enormous dataset). Use `/vg purge config` to cap row
  counts and rate-limit; tuning is an operations concern, not a security
  bug.
- Social engineering, physical access, or attacks against infrastructure
  outside this repository.

If you are unsure whether something is in scope, report it anyway — we
would rather decline a report than miss a real issue.

---

## 6. Acknowledgement

Reporters who follow this policy will be credited in the release notes for
the patched version, via `security@vonix.network`, unless they request
anonymity. Preferred attribution format (name, handle, affiliation, link)
can be included in the report.

VonixGuardian does **not** offer a bug bounty at this time. The project is
volunteer-maintained and has no funding for paid rewards. Acknowledgement
and a CVE credit are what we can offer.

---

## 7. Past Advisories

No security advisories have been issued as of **v1.0.0**.

Future advisories will be listed in this section and tagged in the GitHub
Security Advisories tab of the repository:

<https://github.com/Vonix-Network/VonixGuardian/security/advisories>

---

## See also

- [CONTRIBUTING.md](CONTRIBUTING.md) — general contribution guidelines
- [README.md](README.md) — project overview and installation
