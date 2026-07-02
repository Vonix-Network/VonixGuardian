# Security Policy

## Supported Versions

VonixGuardian follows Semantic Versioning. Only the latest MINOR release line
receives security fixes.

| Version | Supported |
|---------|-----------|
| 1.2.x   | ✅        |
| 1.1.x   | ❌ (fixes rolled forward to 1.2) |
| < 1.1   | ❌        |

## Reporting a Vulnerability

**Do not open a public GitHub issue for security reports.**

Email: `security@vonix.network`
Subject: `[VonixGuardian] <short description>`

Please include:

- Affected version(s) and loader (Fabric / Forge / NeoForge)
- Minecraft version
- Reproduction steps or PoC
- Impact assessment (data disclosure, privilege escalation, RPC abuse, etc.)
- Any suggested mitigation

We will:

1. Acknowledge receipt within **48 hours**.
2. Confirm the vulnerability within **7 days**.
3. Release a fixed version within **30 days** for high-severity issues, or coordinate a longer disclosure window for research-level bugs.

## Scope

In scope:

- Command permission bypasses
- SQL injection through query filter parameters
- Rollback engine writing outside its scope
- IP hashing / salt weaknesses
- Config file traversal or arbitrary file write
- Denial-of-service against the write queue or lookup path
- Unauthenticated API call vectors

Out of scope (report as regular issues):

- Cosmetic bugs
- Performance regressions on very-large modpacks
- Third-party plugin misuse
- CoreProtect behavioural differences that are documented in `docs/COREPROTECT-COMPARISON.md`

## IP Hashing Note

VonixGuardian ships with a **placeholder salt** in the default config
(`vonix-guardian-default-salt-CHANGE-ME`). Since v1.2.0 the config validator
**refuses to boot** when `privacy.hashIps=true` and this placeholder is still
in use — an operator who wants IP hashing must set a real random salt of at
least 16 characters. Operators who do not want IP hashing can leave the salt
at default and set `privacy.hashIps=false`.

Prior versions only emitted a warning. If you are upgrading from ≤1.1.8 with
`hashIps=true` and the default salt, the mod will fail-close on first boot
with a validation error naming the salt. Set a random salt and restart.
