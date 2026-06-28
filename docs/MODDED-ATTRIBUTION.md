# Modded Griefing Attribution

> **The killer feature.** When a tamed dragon eats a village, CoreProtect logs
> `#dragon` and shrugs. VonixGuardian logs `#mob:dragonmounts:fire_dragon`
> **and** the rider's UUID — without ever importing a single class from Dragon
> Mounts, Create, Ice & Fire, or any other mod.

---

## 1. Why this matters

Modded servers break every block-logger built for vanilla. The failure mode is
always the same: a player does something through a mod-supplied entity, the
entity breaks blocks, and the audit log either drops the event entirely or
attributes it to a generic non-player tag. The player walks away clean.

Concrete failures we've watched in production:

- A player tames a **Dragon Mounts 2** fire dragon, flies it over spawn, and
  the dragon's breath weapon vaporises 40 blocks of obsidian. CoreProtect logs
  the block changes against `#fire` or skips them. There is no link back to
  the rider.
- A player builds a **Create** contraption that drives a saw assembly across
  someone else's farm. The contraption's pseudo-entity removes blocks. Most
  loggers don't have a hook for that entity at all.
- A player aims an **Ice and Fire** lightning dragon at a base and watches the
  walls collapse. The damage source is the dragon, not the player.

VonixGuardian closes all three with **zero per-mod code**. The trick is to
lean on vanilla Minecraft's own interfaces — every modded entity already
extends `net.minecraft.world.entity.Entity` and almost all of them already
implement one of the vanilla mixins below. We never `import` a mod class. We
never `Class.forName()` a mod class. We never depend on a mod artifact at
build time. The chain walks public vanilla types and naturally collects
ownership data the mod authors already wired in.

**Universal modded coverage with zero per-mod code.** That is the entire
thesis of this document.

---

## 2. The attribution chain

When a non-player entity griefs — a mob breaks a block, a projectile
detonates, a contraption removes blocks, a dragon's breath weapon vaporises
terrain — VonixGuardian's `AttributionResolver` walks the following chain in
order. The first link that yields a player UUID wins. The sentinel for the
*originating* entity is always recorded alongside, regardless of which link
fires.

The canonical implementation lives in each loader's `*AttributionResolver`
(see `mc-1.20.1/forge/.../ForgeAttributionResolver.java` and its siblings).
The chain:

### (a) Direct player

If the source already *is* a `ServerPlayer`, we're done — record the player
UUID, no sentinel needed (well, the sentinel is recorded for consistency, but
it will never match `#mob:*`).

### (b) Controlling passenger

```java
LivingEntity passenger = e.getControllingPassenger();
if (passenger instanceof Player rider) { ... }
```

A player riding a dragon, a horse, a Create minecart contraption, an Ice and
Fire roc — anything that exposes the rider via vanilla's
`Entity.getControllingPassenger()` — gets blamed for what the mount does.
This is the link that catches the Dragon Mounts case. The rider's UUID is
recorded as `actor_uuid`; the dragon's entity ID is recorded as
`source_tag`.

### (c) `TamableAnimal` owner

```java
if (e instanceof TamableAnimal ta && ta.getOwnerUUID() != null) { ... }
```

Vanilla wolves, cats, parrots, and *every modded creature that extends
`TamableAnimal`* expose their owner via `getOwnerUUID()`. Dragon Mounts
dragons, Alex's Mobs tameables, Ice and Fire dragons in tame state — all
caught here. The owner UUID becomes `actor_uuid`.

### (d) `OwnableEntity` owner (broader interface)

```java
if (e instanceof OwnableEntity oe && oe.getOwnerUUID() != null) { ... }
```

`OwnableEntity` is the broader vanilla interface that `TamableAnimal`
implements. Some mods skip `TamableAnimal` (which carries combat AI baggage)
and implement `OwnableEntity` directly. We catch both.

### (e) `Projectile` owner — recursive

```java
if (e instanceof Projectile p && p.getOwner() != null) {
    Attribution chain = resolveInner(p.getOwner(), now, depth + 1);
    ...
}
```

Arrows, snowballs, throwable potions, modded fireballs from a tamed dragon,
Create's potato cannon shots — anything implementing vanilla's `Projectile`
interface — get resolved by recursing on the projectile's *owner entity*.

That recursion is what makes the chain composable: a player rides a dragon
(link (b)) which fires a fireball (link (e)) which detonates and breaks
blocks. The fireball's owner is the dragon; the dragon's controlling
passenger is the player. The chain bottoms out at the rider's UUID. Depth is
capped (currently 4) to defend against malicious or buggy mods that create
cyclic owner references.

### (f) Recent-damage fallback

If none of the structural links fire, the resolver consults the
`DamageHistory` ring — a short-lived per-entity record of "which player last
hit this thing." This catches the case where a player aggros a mob and the
mob then griefs (e.g. a creeper that the player baited into someone else's
base). The window is short (seconds) and is treated as `INDIRECT`
attribution, not direct ownership.

### (g) NBT scan

The last attempt before giving up: `NbtAttributionScanner.scan(e)` reads the
entity's persistent NBT for well-known owner keys (`Owner`, `OwnerUUID`,
`Tamer`, etc. — all vanilla keys, no mod-specific tags). This catches a few
old mods that store ownership in NBT without implementing
`OwnableEntity`.

### (h) Otherwise — sentinel only

If nothing resolved, the row is written with `actor_uuid = NULL` and the
sentinel describes the source: `#mob:minecraft:wither`, `#natural:raid`,
`#tnt`, etc. The event is still logged and still queryable — just by
sentinel, not by player.

---

## 3. Sentinel tokens

The full list of sentinel tokens is governed by `docs/USAGE.md`. **The
following block is quoted verbatim from `docs/USAGE.md` § Sentinel tokens —
do not edit here.**

> ### Sentinel tokens (modded griefing attribution)
>
> When a non-player griefs, the `actor` column gets a `#sentinel` token rather than a fake username:
>
> ```
> #mob:minecraft:zombie       ← natural zombie
> #mob:minecraft:wither       ← wither (boss)
> #mob:create:contraption     ← Create contraption
> #natural                    ← fire spread, lava flow, leaf decay
> #natural:raid               ← raider in active raid
> #tnt                        ← unprimed TNT (no attacker hooked)
> ```
>
> If our attribution chain caught the responsible player (see "Universal attribution" below), the row stores BOTH the sentinel (in `source_tag`) AND the player UUID (in `actor_uuid`). Lookups can filter on either side.

In addition to the tokens shown in the USAGE block above, the resolver also
emits the natural sub-tokens used by environmental ActionTypes:

```
#natural:fire        ← fire-spread block changes
#natural:lava        ← lava-flow block changes
#natural:explosion   ← non-TNT explosion shrapnel (ghast fireball, creeper)
```

The shape is stable: `#mob:<modid>:<entity_id>` always uses the registry
namespace and path of the source entity's `EntityType`. For vanilla mobs the
modid is `minecraft`. For modded mobs the modid is whatever the mod
registered the entity under — `dragonmounts`, `create`, `iceandfire`,
`alexsmobs`, etc. We don't maintain a list. We don't need to.

---

## 4. Database storage

Every row in the `actions` table carries **both** columns:

| column        | meaning                                                            |
|---------------|--------------------------------------------------------------------|
| `actor_uuid`  | resolved player UUID, or `NULL` if the chain didn't find one       |
| `source_tag`  | sentinel describing the *originating* source (always populated)    |

The two are **independent**. A row can have:

- `actor_uuid = <player>` and `source_tag = NULL` — a player broke a block
  with their own hands.
- `actor_uuid = NULL` and `source_tag = '#natural:lava'` — lava ate a
  chest, no one to blame.
- `actor_uuid = <player>` and `source_tag = '#mob:dragonmounts:fire_dragon'`
  — the killer case: the rider is blamed *and* we remember it was their
  dragon.

Lookups happily filter on either side. `u:Steve` matches the rider rows.
`u:#mob:dragonmounts:fire_dragon` matches every griefing event by any fire
dragon on the server, attributed or not. Operators can also issue
`u:Steve,#mob:dragonmounts:*` to grep both. The query engine treats both
columns as first-class lookup keys.

This dual-column design is the answer to "if you found the player, why store
the sentinel?" — because moderators still want to see *how* the grief
happened. Knowing Steve broke a chest tells you Steve is a griefer. Knowing
Steve broke a chest *via his fire dragon* tells you Steve is exploiting
modded mounts, which is a different policy conversation.

---

## 5. Worked examples

### (a) Dragon mount griefing

A player named `SteveTheTamer` tames a Dragon Mounts 2 fire dragon, mounts
it, and flies it over a neighbour's base. The dragon's breath-weapon AI
fires, and the resulting fire/explosion entity removes 12 blocks of oak
planks.

Chain walk on the fire entity:
1. Not a `ServerPlayer`. Skip.
2. No controlling passenger on the fire entity itself. Skip.
3. Not `TamableAnimal`. Skip.
4. Not `OwnableEntity`. Skip.
5. **`Projectile`?** Dragon Mounts implements the breath as a vanilla-style
   projectile. `getOwner()` returns the dragon entity. Recurse.
   - On the dragon: link (b) fires —
     `dragon.getControllingPassenger()` returns the rider. Bottom out.
6. `actor_uuid = SteveTheTamer.getUUID()`,
   `source_tag = '#mob:dragonmounts:fire_dragon'`.

Twelve `BLOCK_BREAK` rows are written, all with that pair. A moderator's
`/vg lookup u:SteveTheTamer r:50 t:10m` will surface them. So will
`/vg lookup u:#mob:dragonmounts:fire_dragon r:50 t:10m`.

### (b) Create contraption griefing

A player builds a Create mod gantry-mounted saw assembly and drives it
across a neighbour's wheat farm. The contraption's pseudo-entity (whatever
internal class Create uses) breaks the crops.

Chain walk:
1. Not a `ServerPlayer`. Skip.
2. **Controlling passenger?** Create's contraption entities expose the
   controlling rider via vanilla's `getControllingPassenger()` — link (b)
   fires. The rider's UUID becomes `actor_uuid`.
3. `source_tag = '#mob:create:contraption'` (or whatever Create registered
   the entity as; the sentinel uses the registry id).

If the contraption is **unmanned** (an autonomous schematic-cannon-fired
build clearing trees), link (b) doesn't fire, no other link fires either,
and the row is sentinel-only. The grief is still logged and still
attributable to the contraption-type — just not to a player. That is the
correct outcome: nobody was driving.

### (c) Wither griefing

A wither spawns naturally (or, more realistically, a player spawns it and
walks away). It eats a chunk of someone's wall.

Chain walk:
1. Not a player. Skip.
2. No rider. Skip.
3. Not tameable. Skip.
4. Not `OwnableEntity`. Skip.
5. Not a projectile (the wither itself; its skulls *are*, and those would
   recurse to the wither). Skip.
6. No recent player damage in the window. Skip.
7. NBT has no owner key. Skip.
8. Natural classification: not a raider. → `AttributionKind.NATURAL_SPAWN`.

Row written with `actor_uuid = NULL`,
`source_tag = '#mob:minecraft:wither'`. Operators can find it via
`u:#mob:minecraft:wither` but no player is implicated. That is the truthful
answer — the wither was nobody's pet at the moment it griefed.

---

## 6. Why it works without per-mod code

The chain compiles against — and only against — these public Minecraft
types:

- `net.minecraft.world.entity.Entity`
- `net.minecraft.world.entity.LivingEntity`
- `net.minecraft.world.entity.OwnableEntity`
- `net.minecraft.world.entity.TamableAnimal`
- `net.minecraft.world.entity.player.Player`
- `net.minecraft.world.entity.projectile.Projectile`
- `net.minecraft.world.entity.raid.Raider`

These types are part of the vanilla Minecraft jar, ship with every loader
(Forge, NeoForge, Fabric), and are the same types every mod inherits from.
Modded entities are required to extend `Entity` (or a subclass) to be
recognised by the engine at all. Most ownable modded entities implement
`OwnableEntity` or extend `TamableAnimal` because that is how the
ownership/permission/scoreboard machinery wires up in vanilla — mod authors
get it for free by inheriting.

**Reflection is not needed.** `instanceof` checks against public vanilla
interfaces give us everything. The resolver runs at full JIT speed — no
`Method.invoke()`, no `Class.forName()` lookups, no per-frame string
matching. If a mod *doesn't* implement these interfaces, the chain falls
through to sentinel-only, and a future release can add an interface-specific
adapter without touching the resolver core.

The cost of supporting "every mod" is therefore zero. We don't ship a list of
mods. We don't have a compatibility matrix. We don't break when a mod
updates. We don't break when a new mod ships. The chain just keeps walking
vanilla interfaces, and as long as the mod author wired theirs up the way
Mojang's API instructs, we attribute correctly.

---

## 7. Limitations

The chain is *entity-driven*. It traces an entity back to a player. It does
not, and cannot, attribute pure block-driven griefing through this path.

**Block-driven griefing.** A Create mod gantry that pulls a structure via
piston-extend events doesn't always go through an entity — sometimes the
mod fires piston-style block events directly. Those events are caught by
the `piston_extend` `ActionType`, and the source position is logged, but
the *attribution* is best-effort. If the gantry assembly is currently
ridden, we can sometimes hop sideways to the rider via the contraption
entity tracking. If not, the row is sentinel-only.

**Indirect causal chains beyond the damage window.** If a player griefs a
mob into existence (e.g. lures it from far away over several minutes), the
`DamageHistory` window — by design — only retains a few seconds. Long-tail
causal chains are not captured.

**Mods that bypass vanilla ownership.** A mod that stores ownership in its
own custom NBT keys (not `Owner`/`OwnerUUID`/`Tamer`) and does not implement
`OwnableEntity` will not be attributed. We treat this as a mod bug. Almost
no mod in active development does this.

---

## 8. Future work

### Wave-of-blame for chain reactions

The current resolver attributes the *first* link in a chain reaction. The
canonical failure case is cascading TNT:

```
Player primes TNT₀ → TNT₀ explodes →
TNT₁ ignites and primes → TNT₁ explodes →
TNT₂ ignites and primes → TNT₂ explodes → block break
```

TNT₀'s explosion is attributed to the player (via the priming event).
TNT₁ and TNT₂'s explosions and the resulting block breaks are
attributed to `#tnt` with `actor_uuid = NULL`. The player escapes
attribution for the bulk of the destruction.

The fix — call it "wave-of-blame" — is to propagate the originating player
through the explosion event chain. When TNT₀'s explosion ignites TNT₁,
TNT₁'s `PrimedTnt` instance inherits TNT₀'s attribution. When TNT₁'s
explosion ignites TNT₂, the inheritance continues. The chain is bounded by
a depth cap and a time window so that a TNT shop on a public server doesn't
permanently pin "Steve" to every future TNT detonation in that chunk.

This is a roadmap item for v1.1.0. It requires holding lightweight
attribution state on `PrimedTnt` entities and consulting it from the
explosion handler. It does not require any per-mod code.

### Wider passenger-stack walk

Currently we consult `getControllingPassenger()`. Some mods seat a player
in a non-controlling slot (e.g. a Create train passenger seat). We could
walk the full `getPassengers()` list when no controlling passenger exists
and apply a confidence-weighted attribution.

### Configurable NBT key allowlist

Server operators sometimes know that a particular mod stores ownership
under a custom key. Exposing the `NbtAttributionScanner` key list as
config would let them opt in to mod-specific recovery without us shipping
mod-specific code.

---

## See also

- [`docs/USAGE.md`](USAGE.md) — operator-facing command reference, query
  syntax, sentinel token list (authoritative).
- [`docs/API.md`](API.md) — programmatic API for integrating modules,
  including the `AttributionResolver` interface and `Attribution` record.
- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) — module layout, loader split,
  and the boundary between core (vanilla-only) and loader (Forge / Fabric /
  NeoForge) code.
- [`SHARED-CONTRACTS.md`](../SHARED-CONTRACTS.md) — the cross-module
  contract that pins the `actor_uuid` / `source_tag` columns, the sentinel
  grammar in the lookup parser, and the `AttributionResolver` SPI.
