# HTTYD (Isle of Berk) 1.18.2 — Entity Registry

Extracted 2026-07-01 by cracking mod jars and reading `assets/<ns>/lang/en_us.json`.
For use with `actions.entityBlockChangeAllowlist` when server ops want granular control
over which entities' `LivingDestroyBlockEvent`s are audited.

Server: `a0b7f085` (Berk / Claws of Berk)
Loader: Forge 1.18.2-40.3.0
Mods scanned: 103

## Base dragon classes (isleofberk 1.2.0)

The 13 base LivingEntity classes every variant pack extends:

- `isleofberk:deadly_nadder`
- `isleofberk:gronckle`
- `isleofberk:light_fury`
- `isleofberk:monstrous_nightmare`
- `isleofberk:night_fury`
- `isleofberk:night_light`
- `isleofberk:skrill`
- `isleofberk:speed_stinger`
- `isleofberk:speed_stinger_leader`
- `isleofberk:stinger`
- `isleofberk:terrible_terror`
- `isleofberk:triple_stryke`
- `isleofberk:zippleback`

## Additional hostiles

- `inferno:pillager_dragon_hunter` (from `infernos-HTTYD-1.7.2.jar`)

## Variant packs (all under `iobvariantloader:` namespace)

| Pack | Jar | Count | Notes |
|---|---|---:|---|
| dragon_fire | dragon_fire_variants-1.6.1.jar | 12 | night_fury + gronckle variants |
| dragon_wars | dragon_wars_variants-1.6.1.jar | 22 | multi-species faction variants |
| hybridsplus | hybridsplus-2.1.4-forge-1.18.2.jar | 8 | night_fury + light_fury hybrids |
| leafsvariants | leafsvariants-1.0.0.jar | ~313 | grouped by species |

Full expansion in `entity-registry.yml`.

## No-op mods (0 qualifying LivingEntities)

- `novadragons` (DragonsOfTheCosmos) — texture-pack/model-redirect only
- `iobaddons` — items only (dragon_cage, dragon_whistle)
- `dred_dragons` — blocks/items only (dragon eye, book, lens)
- `iobvariantloader` — loader framework itself
- `isleofberk-deadlockfix` — coremod fix
- `domesticationinnovation` — 4 entities but all Projectile/Throwable

## Why this matters for VonixGuardian

Every one of these 300+ dragon variants inherits a `LivingEntity` breaking behavior.
A single flying dragon over unloaded chunks fires `LivingDestroyBlockEvent` for every
block its collision box passes through — hence the 200k events/sec producer rate that
overwhelmed the async write queue on 2026-07-01.

Mitigation (v1.1.4): producer-side coalescer + queue capacity headroom.
Optional per-server tuning: `actions.entityBlockChangeAllowlist` in config.
