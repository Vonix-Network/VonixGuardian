# VonixGuardian — Permission Reference

VonixGuardian v1.0.0 — complete permission node reference.

## 1. Overview

VonixGuardian gates every `/vg` subcommand behind a permission node of the form
`vonixguardian.command.<sub>` (e.g. `vonixguardian.command.rollback`). The
permission prefix is declared in `CommandSpec.PERMISSION_PREFIX` and is
identical across every supported loader (Forge, NeoForge, Fabric) and every
supported Minecraft version (1.18.2, 1.19.2, 1.20.1, 1.21.1).

Resolution happens through `network.vonix.guardian.core.perms.PermissionResolver`:

1. **LuckPerms (preferred).** If `permissions.useLuckPerms` is `true` *and*
   LuckPerms is on the classpath, the check is delegated to LuckPerms through
   `LuckPermsBridge` — a **pure-reflection** bridge that never `import`s
   `net.luckperms.*`. LuckPerms is therefore a **soft dependency**: VonixGuardian
   compiles, ships, and runs without it. Detection is performed once per JVM via
   `Class.forName("net.luckperms.api.LuckPermsProvider")` and cached as a
   tri-state (`null`/unprobed, `TRUE`/available, `FALSE`/permanently absent).
   `IllegalStateException` (LP loaded but not yet registered during early boot)
   is *not* cached — the next call re-probes.

2. **Op-level fallback.** If LuckPerms is disabled in config, missing from the
   classpath, or returns `UNDEFINED`, the resolver consults the loader-supplied
   `OpLevelFallback` and grants iff `opLevel >= permissions.defaultOpLevel`.
   The default `defaultOpLevel` is `3`, i.e. server-op level 3 (the standard
   "op" tier). Without LuckPerms there is **no granular control** — any
   sufficiently opped player can run every `/vg` subcommand.

The console (server source) is always treated as fully authorised by the
loader command layer.

## 2. Permission node table

Every node is a literal string. The first ten are emitted by `CommandSpec` and
are checked by the loader's brigadier tree (`GuardianCommands#register`). The
last two — `bypass` and `viewothers` — are gates queried inside the action
pipeline and the lookup/rollback handlers respectively, not on the command
node itself.

| Node                              | Gates                                          | Description |
|-----------------------------------|------------------------------------------------|-------------|
| `vonixguardian.command.use`       | the `/vg` and `/guardian` root literal         | Required to even see the command in tab-complete. Held implicitly by anyone holding any subcommand node in practice, but checked first on the root brigadier node. |
| `vonixguardian.command.inspect`   | `/vg inspect` (alias `/vg i`)                  | Toggle inspection mode; left-/right-click a block to query its history. |
| `vonixguardian.command.lookup`    | `/vg lookup <filter…>`, `/vg near` (alias `n`) | Query the audit log with filter tokens (`u:` `t:` `r:` `a:` `i:` `e:` `#…`); `near` is a small-radius shorthand. |
| `vonixguardian.command.rollback`  | `/vg rollback <filter…>` (alias `rb`), `/vg undo` | Undo matching logged actions. `/vg undo` reverses *your own* last rollback/restore and is gated by the same node. |
| `vonixguardian.command.restore`   | `/vg restore <filter…>` (alias `rs`)           | Re-apply actions previously undone by `/vg rollback`. |
| `vonixguardian.command.purge`     | `/vg purge <time>`                             | **Destructive.** Permanently deletes audit rows older than the given duration. Cannot be undone. |
| `vonixguardian.command.near`      | `/vg near` (alias `n`)                         | Listed by `CommandSpec` for completeness. The loader command tree currently shares the `lookup` node for `near`; if you grant `lookup` you grant `near`. |
| `vonixguardian.command.undo`      | `/vg undo`                                     | Listed by `CommandSpec`. The loader tree gates `undo` under `rollback`; grant `rollback` to allow `undo`. |
| `vonixguardian.command.status`    | `/vg status`                                   | Show queue depth, DB health, schema version, mod/MC/loader version. Read-only. |
| `vonixguardian.command.reload`    | `/vg reload`                                   | Reload `config/vonixguardian/config.json` from disk. Some fields (`database.type`, `queue.maxSize`) require a server restart and are reported as `requires restart`. |
| `vonixguardian.command.bypass`    | the action-logging pipeline                    | **Actions performed by holders of this node are NOT written to the audit log.** Not gating any subcommand — it suppresses logging at the source. See § 5. |
| `vonixguardian.command.viewothers`| `/vg lookup` and `/vg rollback` filter scoping | Allows targeting other users without an explicit `u:<name>` filter token. Without it, queries are auto-scoped to the executor. See § 6. |

> The node strings in this table are the same verbatim strings hard-coded in
> the loader command modules
> (`mc-<ver>/<loader>/.../common/GuardianCommands.java`) and listed in
> `docs/USAGE.md` § "LuckPerms integration". They are part of the v1.0.0
> public contract — changing them is a breaking change.

## 3. Permission group example (LuckPerms)

A typical two-tier setup: **moderators** can investigate and inspect but cannot
mutate the world or wipe data; **admins** get the full kit.

```text
# Moderator — read-only investigation
/lp group moderator permission set vonixguardian.command.use         true
/lp group moderator permission set vonixguardian.command.inspect     true
/lp group moderator permission set vonixguardian.command.lookup      true
/lp group moderator permission set vonixguardian.command.near        true
/lp group moderator permission set vonixguardian.command.status      true
/lp group moderator permission set vonixguardian.command.viewothers  true

# Admin — inherits moderator + mutation + maintenance
/lp group admin parent add moderator
/lp group admin permission set vonixguardian.command.rollback        true
/lp group admin permission set vonixguardian.command.restore         true
/lp group admin permission set vonixguardian.command.undo            true
/lp group admin permission set vonixguardian.command.purge           true
/lp group admin permission set vonixguardian.command.reload          true
```

Notes:

- Do **not** grant `vonixguardian.command.bypass` to a human role. It is
  intended for the server console and trusted automation only (see § 5).
- `vonixguardian.command.viewothers` is what lets a moderator look up
  *anyone's* edits in a region without typing `u:<name>` for every query.
  Without it, `/vg lookup r:20` only returns the moderator's own actions
  (see § 6).
- The `use` node is what gates the root `/vg` literal — granting only a
  subcommand node without `use` will hide the command from tab-completion on
  some loaders. Always grant `use` to any role that holds any other VG node.

## 4. Fallback when LuckPerms is absent

When LuckPerms is not on the classpath, or when `permissions.useLuckPerms` is
explicitly set to `false` in `config/vonixguardian/config.json`, the resolver
falls back to vanilla op level. The relevant config record is
`GuardianConfig.Permissions(boolean useLuckPerms, int defaultOpLevel)`.

```jsonc
{
  "permissions": {
    "useLuckPerms": true,   // try LP first; harmless if LP isn't installed
    "defaultOpLevel": 3     // [0, 4] — 3 == standard server op
  }
}
```

Semantics:

- `defaultOpLevel` is validated by `ConfigLoader` to lie in `[0, 4]`; values
  outside that range reject the config with
  `permissions.defaultOpLevel: must be in [0,4]`.
- The default `3` matches the vanilla "op" tier (level 3 is the default for
  `/op <player>` on most servers).
- Holders of an op level `>= defaultOpLevel` are granted **every** VG node
  unconditionally — including `bypass` and `viewothers`. There is no granular
  control without LuckPerms.
- The console always passes because the loader command layer short-circuits
  permission checks for non-player sources.
- An exception thrown by the loader-supplied `OpLevelFallback` causes a
  **deny** (logged at WARN) — fail-closed.

If you need per-command control without running LuckPerms, you must run a
permission mod. Pterodactyl- or panel-managed servers can install LuckPerms
side-by-side with VonixGuardian — VG will pick it up automatically on next
reload.

## 5. `vonixguardian.command.bypass` semantics

Holders of `vonixguardian.command.bypass` produce world mutations that are
**never written to the audit log.** This is enforced inside the action
pipeline — the event sentinels query the resolver and short-circuit
`EventSubmitter.submit(...)` when the actor has the node.

⚠️ **Security implications.** This node defeats the entire audit chain for the
holder. A griefer who somehow obtained it could destroy your world *and there
would be no record to roll back from*. Therefore:

- **Do not grant `bypass` to a human role.** Not moderator, not admin, not
  even owner.
- Reasonable use cases are limited to:
  - The server console / automation accounts that generate large volumes of
    procedural world edits (e.g. WorldEdit replays, scripted regeneration)
    where logging would balloon the database without adding investigative
    value.
  - Trusted bots performing scheduled tasks (e.g. arena resets) where the
    actions are already audited elsewhere.
- If you grant `bypass`, document *why* and *to whom* externally. The audit
  log itself cannot tell you who the bypassed actor was, by design.

## 6. `vonixguardian.command.viewothers` semantics

`viewothers` controls **filter scoping** on `/vg lookup`, `/vg near`, and
`/vg rollback`/`/vg restore`. It does *not* gate the subcommand — you also
need the corresponding `lookup` or `rollback` node.

Behaviour:

- **Without `viewothers`:** if the executor does **not** supply an explicit
  `u:<name>` filter token, the query is implicitly scoped to the executor's
  own UUID. `/vg lookup r:20` becomes `/vg lookup u:<self> r:20`. The
  executor sees only their own actions in the region. An explicit
  `u:someoneelse` is rejected with a permission error.
- **With `viewothers`:** no implicit scoping. `/vg lookup r:20` returns
  *all* actors' actions in the region; `u:<name>` filters work normally for
  any target.

The intent is to let a server hand out `lookup` to regular players (so they
can audit their own builds without staff intervention) while reserving the
ability to investigate other players for trusted staff via `viewothers`.

## See also

- [`docs/USAGE.md`](USAGE.md) — full command reference and filter token grammar.
- [`docs/CONFIG.md`](CONFIG.md) — `permissions.useLuckPerms` and
  `permissions.defaultOpLevel` field reference plus full config schema.
