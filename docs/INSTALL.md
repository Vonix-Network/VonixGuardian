# VonixGuardian — Install Guide (v1.0.0)

VonixGuardian is a **server-side mod**. It does **not** need to be installed on clients. Players join with a vanilla / regular modpack client; only the dedicated server (or single-player integrated server) needs the jar in `mods/`.

This guide covers a clean install of v1.0.0 on a dedicated server.

## Prerequisites

- A **Minecraft server** running one of the supported loader + version combinations below.
- **Java** matching the Minecraft version:
  - Minecraft **1.21.1** → **Java 21**
  - Minecraft **1.20.1 / 1.19.2 / 1.18.2** → **Java 17**
- Write access to the server directory (the mod creates `config/`, `logs/`, and a SQLite DB file).
- A few hundred MB of free disk for the audit database (grows with activity).

Supported loader + MC matrix for v1.0.0:

| Minecraft | Fabric | Forge | NeoForge |
|-----------|--------|-------|----------|
| 1.21.1    | ✅     | —     | ✅       |
| 1.20.1    | ✅     | ✅    | —        |
| 1.19.2    | ✅     | ✅    | —        |
| 1.18.2    | ✅     | ✅    | —        |

No other plugin or library is required. SQLite is bundled inside the jar.

## Download

Release page: <https://github.com/Vonix-Network/VonixGuardian/releases/tag/v1.0.0>

Pick the **one** jar that matches your server's loader and Minecraft version:

| File | Loader | MC |
|------|--------|----|
| `vonixguardian-fabric-1.21.1-1.0.0.jar`   | Fabric   | 1.21.1 |
| `vonixguardian-neoforge-1.21.1-1.0.0.jar` | NeoForge | 1.21.1 |
| `vonixguardian-fabric-1.20.1-1.0.0.jar`   | Fabric   | 1.20.1 |
| `vonixguardian-forge-1.20.1-1.0.0.jar`    | Forge    | 1.20.1 |
| `vonixguardian-fabric-1.19.2-1.0.0.jar`   | Fabric   | 1.19.2 |
| `vonixguardian-forge-1.19.2-1.0.0.jar`    | Forge    | 1.19.2 |
| `vonixguardian-fabric-1.18.2-1.0.0.jar`   | Fabric   | 1.18.2 |
| `vonixguardian-forge-1.18.2-1.0.0.jar`    | Forge    | 1.18.2 |

Also download `SHA256SUMS` from the same release page.

Example (download the 1.20.1 Forge jar and the checksum file):

```bash
curl -LO https://github.com/Vonix-Network/VonixGuardian/releases/download/v1.0.0/vonixguardian-forge-1.20.1-1.0.0.jar
curl -LO https://github.com/Vonix-Network/VonixGuardian/releases/download/v1.0.0/SHA256SUMS
```

## Verify the download

Verify the jar against the published checksums **before** placing it on the server:

```bash
sha256sum -c SHA256SUMS --ignore-missing
```

Expected output (for the jar you downloaded):

```
vonixguardian-forge-1.20.1-1.0.0.jar: OK
```

If you see `FAILED`, delete the jar and re-download. Do not run unverified jars.

## File placement

Stop the server, then drop the verified jar into the server's `mods/` directory:

```bash
cp vonixguardian-forge-1.20.1-1.0.0.jar /path/to/server/mods/
```

That is the entire install step. There is no separate library, coremod, or config to pre-stage. The mod ships its SQLite driver inside its own jar.

Do **not** place the jar on client installations. Doing so is harmless on Fabric/NeoForge (it will simply do nothing without a server world to attach to) but is unnecessary and unsupported.

## First boot

Start (or restart) the server normally — there is no special launch flag.

On first boot, VonixGuardian creates the following, relative to the server root:

- `config/vonixguardian/config.json` — auto-generated with defaults. Edit and `/vg reload` to apply most fields; some require a restart (see `docs/CONFIG.md`).
- `vonixguardian.db` — SQLite database in the server root. Holds the audit log and rollback batches.
- `logs/vonixguardian/` — directory for VonixGuardian's own log output (in addition to the standard server log).

No directories need to be pre-created; the mod creates them on demand.

### Restart procedure

For an existing server that has been running without VonixGuardian:

1. Stop the server cleanly (`/stop` from console or in-game).
2. Wait for the process to exit.
3. Copy the jar into `mods/`.
4. Start the server.
5. Watch the log for the lines shown under **Verifying installation** below.

For containerised setups (Docker, Pterodactyl, etc.), use the panel's stop/start controls rather than `kill` — a clean shutdown lets vanilla world data flush before VonixGuardian's schema is initialised.

## Verifying installation

Two log lines confirm a successful install. Both are emitted under the `VonixGuardian` marker.

Look for the **load line** early in startup:

```
[main/INFO] [VonixGuardian]: VonixGuardian (Forge 1.20.1) loading.
```

The loader and version in parentheses will match the jar you installed — e.g. `Fabric 1.21.1`, `NeoForge 1.21.1`, `Forge 1.18.2`, etc.

Then look for the **bootstrap complete** line, which is emitted after the engine is up, the SQLite schema is initialised, and the `/vg` command tree is registered:

```
[Server thread/INFO] [VonixGuardian]: VonixGuardian bootstrap complete.
```

`bootstrap complete.` appears **before** Minecraft's own `Done (Xs)!` line. If `Done` prints first and `bootstrap complete.` never appears, treat it as a failed install.

Once players can log in, run in-game (or from the server console):

```
/vg status
```

Expected output reports:

- DB type + connection pool stats (active / idle / waiting)
- Queue depth + drop counter
- Last write latency (p50, p99)
- Schema version
- Mod version + MC version + loader

A healthy fresh install shows zero drops, a low queue depth, and the schema version at the current release's value.

## Common installation issues

If the load line never appears, `/vg status` reports an error, or the server crashes during boot, see the **Troubleshooting** section of the usage guide:

- [`docs/USAGE.md#troubleshooting`](USAGE.md#troubleshooting)

That section covers the usual culprits: wrong loader jar for the MC version, Java version mismatch (1.21.1 requires Java 21), `mods/` directory not being read by the launcher, leftover dev/snapshot jars conflicting with the v1.0.0 jar, and permission errors writing `vonixguardian.db` or `config/vonixguardian/`.

## See also

- [`README.md`](../README.md) — project overview and feature summary
- [`docs/USAGE.md`](USAGE.md) — commands, rollback workflow, troubleshooting
- [`docs/CONFIG.md`](CONFIG.md) — `config/vonixguardian/config.json` reference
