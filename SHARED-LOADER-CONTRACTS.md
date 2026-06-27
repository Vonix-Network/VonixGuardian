# VonixGuardian — Loader Module Contracts

**This document is the single source of truth for how each loader (Fabric / Forge / NeoForge per MC version) translates Minecraft events into `core/` engine submissions.**

Subagents working on loader modules MUST quote types verbatim from this file. Parent owns this doc.

---

## 1. Module structure (per MC version)

```
mc-<ver>/
├── common/                  ← MC-version Mojmap code shared by every loader
│   └── src/main/java/network/vonix/guardian/mc/<ver>/common/
│       ├── GuardianCommands.java        ← brigadier command tree
│       ├── ChatRenderer.java            ← Theme → Component conversion
│       ├── EntitySentinel.java          ← #mob:<ns>:<path> resolver
│       ├── SourceTagger.java            ← infer sourceTag from damage source / cause
│       ├── WorldKey.java                ← Level → "minecraft:overworld" string
│       └── (loader-AGNOSTIC code only)
│
├── fabric/                  ← Fabric loader entrypoint + event registrations
│   └── src/main/java/.../fabric/
│       ├── VonixGuardianFabric.java     ← ModInitializer entrypoint
│       ├── FabricEvents.java            ← event registrations
│       ├── FabricWorldMutator.java      ← WorldMutator impl
│       ├── FabricAttributionResolver.java ← AttributionResolver impl
│       └── FabricOpLookup.java          ← OpLevelFallback impl
│
├── forge/                   ← (1.18.2 / 1.19.2 / 1.20.1) Forge entrypoint
│   └── src/main/java/.../forge/
│       ├── VonixGuardianForge.java      ← @Mod entrypoint
│       ├── ForgeEvents.java             ← @SubscribeEvent handlers
│       ├── ForgeWorldMutator.java
│       ├── ForgeAttributionResolver.java
│       └── ForgeOpLookup.java
│
└── neoforge/                ← (1.21.1) NeoForge entrypoint (renamed event package)
    └── src/main/java/.../neoforge/
        ├── VonixGuardianNeoForge.java   ← @Mod entrypoint
        ├── NeoForgeEvents.java          ← @SubscribeEvent handlers
        ├── NeoForgeWorldMutator.java
        ├── NeoForgeAttributionResolver.java
        └── NeoForgeOpLookup.java
```

---

## 2. Mod entrypoint contract (every loader)

Every loader must perform these steps **in this exact order** during server start:

```java
// 1. Resolve dataDir = server.getServerDirectory().toPath()
//    (1.18.2 returns File — convert to Path)
// 2. Load config (creates default if missing)
GuardianConfig config = ConfigLoader.load(dataDir.resolve("config/vonixguardian/config.json"));

// 3. Build the WorldMutator (loader-side)
WorldMutator mutator = new <Loader>WorldMutator(server);

// 4. Build the OpLevelFallback (loader-side)
OpLevelFallback opLookup = new <Loader>OpLookup(server);

// 5. Build the main-thread executor — server::execute (Mojmap on every version)
Executor mainThread = server::execute;

// 6. Daemon thread factory for queue worker
ThreadFactory tf = r -> {
    Thread t = new Thread(r, "VonixGuardian-Writer");
    t.setDaemon(true);
    return t;
};

// 7. Boot Guardian — wires queue, dao, logfile, rollback, perms, theme
Guardian g = Guardian.boot(config, dataDir, mutator, opLookup, mainThread, tf);

// 8. Build AttributionResolver + DamageHistory (loader-side)
DamageHistory dh = new DamageHistory();
AttributionResolver resolver = new <Loader>AttributionResolver(dh);

// 9. Register events — pass g.submitter() + resolver + dh
<Loader>Events.register(g, resolver, dh);

// 10. Register brigadier commands — uses g + GuardianCommands.build()
//     (CommandRegistrationCallback on Fabric, RegisterCommandsEvent on Forge/NeoForge)
GuardianCommands.register(dispatcher, g);

// 11. On server stop: g.close()
```

The Guardian instance is stored in a `static volatile` field on the entrypoint class so other classes can fetch it via `<Loader>EntryPoint.guardian()`. Loader-supplied classes (WorldMutator, AttributionResolver, OpLookup) hold their own references — they DON'T look up via the static.

---

## 3. Universal event → core mapping (apply on every loader)

| MC event (vanilla, fires for every entity inc. modded) | Guardian.submitter() call | Sentinel resolution |
|---|---|---|
| **BlockEvent.BreakEvent** | `submitBlockBreak(player, name, world, x, y, z, blockId, sourceTag)` | actor = player (always a player event) |
| **BlockEvent.EntityPlaceEvent** | `submitBlockPlace(actor, name, world, x, y, z, blockId, sourceTag)` | actor resolved via AttributionResolver |
| **BlockEvent.NeighborNotifyEvent** → fire spread, fluid flow | `submitSpread / submitFromTo / submitBucketEmpty` | sourceTag = "#fluid:water" / "#fire" |
| **BlockEvent.BlockToolModificationEvent** | (skip; tools don't grief) | — |
| **LivingDestroyBlockEvent** | `submitEntityChangeBlock(actor_via_resolver, sentinel, world, x,y,z, oldBlockId, newBlockId, sourceTag)` | **THE universal griefing path** — every mob, every dragon, every modded entity |
| **ExplosionEvent.Detonate** | `submitExplosion(actor_via_resolver, sentinel, world, x, y, z, affectedJoined, sourceTag)` | resolver walks projectile.getOwner() if explosion source is a fireball |
| **EntityMobGriefingEvent** | (predicate-only; do not log here, log at the resulting BlockEvent) | — |
| **EntityTeleportEvent.EnderEntity** + carrying block | `submitBlockBreak("#mob:minecraft:enderman", world, src) + submitBlockPlace(same, dst)` | sourceTag = "ender_teleport" |
| **LivingDeathEvent** | `submitEntityKill(killer_via_resolver, sentinel, world, x, y, z, victim_entity_type, sourceTag)` | killer resolved via damage source + AttributionResolver; if no killer, NATURAL |
| **EntityJoinLevelEvent** (entity spawn) | gate by `config.actions.logEntities` → `submitEntitySpawn(...)` | rate-limit per entity type to avoid spam |
| **ServerChatEvent** (NeoForge) / `ServerMessageEvents.CHAT_MESSAGE` (Fabric) | `submitChat(playerUuid, name, worldId, message)` | — |
| **CommandEvent** (NeoForge) / `CommandRegistrationCallback` + wrap (Fabric) | `submitCommand(playerUuid, name, worldId, "/" + command)` | — |
| **SignChangeEvent** (no longer exists in 1.20+; mixin or PlayerInteractEvent) | `submitSign(playerUuid, name, worldId, x, y, z, joinedLines)` | — |
| **PlayerEvent.PlayerLoggedInEvent** | `submitSessionJoin(uuid, name, worldId, IpHasher.hash(ip, salt))` | — |
| **PlayerEvent.PlayerLoggedOutEvent** | `submitSessionLeave(uuid, name, worldId, reason)` | — |
| **PlayerInteractEvent.RightClickBlock** | `submitClick(uuid, name, world, x, y, z, blockId)` if `cfg.actions.logInteractions` | — |
| **PlayerContainerEvent.Open** + slot change | `submitContainerChange(uuid, name, world, x, y, z, itemId, delta, sourceTag)` | track via per-player open inventory snapshot diff |
| **ItemTossEvent** | `submitItemDrop(uuid, name, world, x, y, z, itemId, count, sourceTag)` | sourceTag = "death" if player was killed |
| **ItemPickupEvent** | `submitItemPickup(...)` | — |
| **PlayerEvent.ItemCraftedEvent** | `submitItemCraft(uuid, name, world, x, y, z, itemId, count, recipeId)` | — |
| **BlockEvent.BurnEvent** | `submitBurn(...)` | — |
| **BlockEvent.IgniteEvent** | `submitIgnite(...)` | sourceTag = "#fire" / "#lava" / "#lightning" |
| **BlockEvent.FluidPlaceBlockEvent** (BUCKET_EMPTY / form) | `submitBucketEmpty(uuid, name, world, x, y, z, fluidId)` or `submitForm(...)` | — |
| **FillBucketEvent** | `submitBucketFill(uuid, name, world, x, y, z, fluidId)` | — |
| **PistonEvent.Pre** | `submitPistonExtend/Retract(...)` | — |
| **LeavesDecayEvent** (mixin or BlockEvent.NeighborNotify) | `submitLeavesDecay(...)` | — |

---

## 4. AttributionResolver impl skeleton (universal chain)

```java
public final class <Loader>AttributionResolver implements AttributionResolver {

    private final DamageHistory damageHistory;
    private static final int MAX_RECURSION = 4;

    public Attribution resolve(Object handle, long now) {
        if (!(handle instanceof Entity e)) return Attribution.unknown("#unknown");
        return resolveInner(e, now, 0);
    }

    private Attribution resolveInner(Entity e, long now, int depth) {
        if (depth >= MAX_RECURSION) return Attribution.unknown(sentinel(e));

        String sentinel = sentinel(e);

        // 1. Player passenger (rider)
        Entity passenger = e.getControllingPassenger();
        if (passenger instanceof Player rider) {
            return Attribution.rider(rider.getUUID(), rider.getName().getString(), sentinel);
        }

        // 2. TamableAnimal owner (vanilla interface; every tameable mod inherits)
        if (e instanceof TamableAnimal t && t.getOwnerUUID() != null) {
            return Attribution.owner(t.getOwnerUUID(),
                lookupName(t.getOwnerUUID()), sentinel);
        }
        // 2b. OwnableEntity interface (1.20.2+; Ars Nouveau, Iron's Spells)
        if (e instanceof OwnableEntity oe && oe.getOwnerUUID() != null) {
            return Attribution.owner(oe.getOwnerUUID(),
                lookupName(oe.getOwnerUUID()), sentinel);
        }

        // 3. Projectile owner — recurse
        if (e instanceof Projectile p && p.getOwner() != null) {
            Attribution chain = resolveInner(p.getOwner(), now, depth + 1);
            return chain.kind().isPlayer()
                ? Attribution.projectile(chain.actorUuid(), chain.actorName(), sentinel, depth + 1)
                : chain;
        }

        // 4. Recent damage history
        UUID recent = damageHistory.lastPlayerToHit(e.getUUID(), now);
        if (recent != null) {
            return Attribution.indirect(recent, lookupName(recent), sentinel);
        }

        // 5. NBT scan (Create deployerUUID, Mekanism ownerUUID, generic Summoner)
        UUID nbtOwner = NbtAttributionScanner.scan(e);
        if (nbtOwner != null) {
            return Attribution.tamer(nbtOwner, lookupName(nbtOwner), sentinel, 2);
        }

        // 6. Natural classification
        return Attribution.natural(classifyNatural(e), sentinel);
    }

    private String sentinel(Entity e) {
        return "#mob:" + EntityType.getKey(e.getType()).toString();
    }

    private AttributionKind classifyNatural(Entity e) {
        if (e instanceof Raider r && r.getCurrentRaid() != null) return AttributionKind.NATURAL_RAID;
        // ... structure check via level.structureManager().getStructureWithPieceAt(...)
        return AttributionKind.NATURAL_SPAWN;
    }
}
```

`NbtAttributionScanner` is loader-agnostic and lives in `core/attribution/`. Loader supplies a `Function<Entity, CompoundTag>` adapter.

---

## 5. WorldMutator impl skeleton

```java
public final class <Loader>WorldMutator implements WorldMutator {
    private final MinecraftServer server;

    public void setBlock(String worldId, int x, int y, int z, String targetId, String meta) {
        ServerLevel level = level(worldId);
        if (level == null) return;
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(targetId));
        if (block == null) return;
        BlockState state = (meta != null && !meta.isEmpty())
            ? NbtCodec.parseBlockState(meta, block.defaultBlockState())
            : block.defaultBlockState();
        level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL);
    }

    public void giveOrDrop(String worldId, int x, int y, int z, String itemId, int amount, String meta) {
        ServerLevel level = level(worldId);
        if (level == null) return;
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == null) return;
        ItemStack stack = new ItemStack(item, Math.max(1, amount));
        if (meta != null) NbtCodec.applyItemMeta(stack, meta);

        BlockPos pos = new BlockPos(x, y, z);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container c && tryInsert(c, stack)) return;

        // Drop on ground
        ItemEntity drop = new ItemEntity(level, x + 0.5, y + 0.5, z + 0.5, stack);
        drop.setDefaultPickUpDelay();
        level.addFreshEntity(drop);
    }
    // ... removeFromContainer, respawnEntity similar
}
```

---

## 6. Command tree (brigadier) — `GuardianCommands.register`

```java
public final class GuardianCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Guardian g) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("vg")
            .requires(s -> hasPerm(s, "vonixguardian.command.use", g))
            .then(literal("inspect").requires(s -> hasPerm(s, "vonixguardian.command.inspect", g))
                .executes(ctx -> Inspect.toggle(ctx, g)))
            .then(literal("lookup").requires(s -> hasPerm(s, "vonixguardian.command.lookup", g))
                .then(argument("filter", StringArgumentType.greedyString())
                    .executes(ctx -> Lookup.run(ctx, g))))
            .then(literal("rollback").requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                .then(argument("filter", StringArgumentType.greedyString())
                    .executes(ctx -> Rollback.run(ctx, g, false))))
            .then(literal("restore").requires(s -> hasPerm(s, "vonixguardian.command.restore", g))
                .then(argument("filter", StringArgumentType.greedyString())
                    .executes(ctx -> Restore.run(ctx, g))))
            .then(literal("purge").requires(s -> hasPerm(s, "vonixguardian.command.purge", g))
                .then(argument("filter", StringArgumentType.greedyString())
                    .executes(ctx -> Purge.run(ctx, g))))
            .then(literal("near").requires(s -> hasPerm(s, "vonixguardian.command.lookup", g))
                .executes(ctx -> Lookup.near(ctx, g, 5)))
            .then(literal("undo").requires(s -> hasPerm(s, "vonixguardian.command.rollback", g))
                .executes(ctx -> Undo.run(ctx, g)))
            .then(literal("status").requires(s -> hasPerm(s, "vonixguardian.command.status", g))
                .executes(ctx -> Status.run(ctx, g)))
            .then(literal("reload").requires(s -> hasPerm(s, "vonixguardian.command.reload", g))
                .executes(ctx -> Reload.run(ctx, g)))
            .then(literal("help").executes(ctx -> Help.run(ctx, g)));

        dispatcher.register(root);
        dispatcher.register(literal("guardian").redirect(dispatcher.getRoot().getChild("vg")));
    }

    private static boolean hasPerm(CommandSourceStack s, String node, Guardian g) {
        if (!(s.getEntity() instanceof ServerPlayer p)) {
            return s.hasPermission(g.config().permissions().defaultOpLevel());
        }
        return g.perms().has(p.getUUID(), node);
    }
}
```

Lookup / Rollback / Restore / Purge / Inspect / Undo / Status / Reload / Help all run on the **server thread** (brigadier callback). They dispatch the actual DB query to a worker thread, then `server::execute` back to send chat results.

---

## 7. Inspector contract

`Inspect.toggle(ctx, g)` flips a per-player `boolean inspecting` flag stored in a `Map<UUID, InspectorState>` on the entrypoint class. Loader's PlayerInteractEvent handler checks the flag first — if inspecting, **cancel the event** and run a position lookup instead.

```java
@SubscribeEvent
public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
    if (Inspector.isInspecting(e.getEntity().getUUID())) {
        e.setCanceled(true);
        BlockPos p = e.getPos();
        Lookup.atPos(g, p.getX(), p.getY(), p.getZ(), e.getLevel(), e.getEntity());
    }
}
```

---

## 8. Source tagging

`SourceTagger.tag(DamageSource | Entity | BlockState | Cause)` returns a stable sourceTag string used by all events that have a "why":

| Cause | sourceTag |
|---|---|
| TNT explosion | `"#tnt"` |
| Creeper explosion | `"#creeper"` |
| Bed explosion (nether) | `"#bed"` |
| Lava ignite | `"#lava"` |
| Lightning ignite | `"#lightning"` |
| Fire spread | `"#fire"` |
| Water flow | `"#fluid:water"` |
| Lava flow | `"#fluid:lava"` |
| Piston push | `"#piston"` |
| Player damage (item drop on death) | `"death"` |
| Fall damage | `"#fall"` |
| Drowning | `"#drown"` |
| Suffocation | `"#suffocate"` |
| End crystal | `"#end_crystal"` |
| Wither skull | `"#wither_skull"` |
| Fireball (projectile owner unknown) | `"#fireball"` |
| (modded source, unknown) | `"#modded:" + modId` |

---

## 9. Loader-specific quirks (per MC version)

### 1.21.1 NeoForge
- Mod entry: `@Mod("vonixguardian")` on `VonixGuardianNeoForge`.
- `IEventBus modBus = context.getModEventBus()` for FMLClientSetupEvent etc; we use NEOFORGE bus (`NeoForge.EVENT_BUS`) for runtime events.
- `RegisterCommandsEvent` for command tree.
- `ServerChatEvent` lives in `net.neoforged.neoforge.event.ServerChatEvent`.
- `EntityType.getKey(entity.getType())` is the modern resource location lookup.
- Java 21 required.

### 1.21.1 Fabric
- Mod entry: `ModInitializer` declared in `fabric.mod.json`.
- `ServerLifecycleEvents.SERVER_STARTING` for Guardian.boot().
- `ServerLifecycleEvents.SERVER_STOPPING` for Guardian.close().
- `CommandRegistrationCallback.EVENT` for commands.
- `ServerMessageEvents.CHAT_MESSAGE` for chat capture.
- No direct block-place event before 1.21 — use a Mixin into `Level.setBlock` filtered by caller entity. Mixin target: `net.minecraft.world.level.Level`, method `setBlock(BlockPos,BlockState,int,int)`.
- Java 21.

### 1.20.1 Forge
- Mod entry: `@Mod("vonixguardian")`.
- `MinecraftForge.EVENT_BUS.register(ForgeEvents.class)`.
- `RegisterCommandsEvent` works.
- `ServerChatEvent` lives in `net.minecraftforge.event.ServerChatEvent`.
- Java 17.

### 1.20.1 Fabric
- Same as 1.21.1 Fabric pattern; older API package layouts.
- `ServerMessageEvents.CHAT_MESSAGE` available.
- Java 17.

### 1.19.2 Forge
- `RegisterCommandsEvent` works.
- `ServerChatEvent` was added in this version — fires on each player chat.
- No `LivingDestroyBlockEvent` — use `EntityMobGriefingEvent` + `BlockEvent.BreakEvent` with caller-entity filter (use a coremod-free reflective hook).
- Java 17.

### 1.19.2 Fabric
- `ServerMessageEvents.CHAT_MESSAGE` API present (introduced ~1.19).
- Same Mixin requirement for setBlock-by-entity.

### 1.18.2 Forge
- ForgeGradle 5 (NOT 6).
- `RegisterCommandsEvent` works.
- `ServerChatEvent` available.
- No `LivingDestroyBlockEvent` — use `EntityMobGriefingEvent` + caller filter.
- Java 17. **`getOwnerUUID()` exists on `TamableAnimal` (was called `OwnableEntity` interface).**

### 1.18.2 Fabric
- fabric-loom 0.12.x
- No `ServerMessageEvents.CHAT_MESSAGE` API — must use ServerPlayConnectionEvents or a Mixin.
- Java 17.

---

## 10. Resources

- `META-INF/neoforge.mods.toml` (NeoForge 1.21.1)
- `META-INF/mods.toml` (Forge 1.18.2 / 1.19.2 / 1.20.1)
- `fabric.mod.json` (Fabric, all versions)
- `pack.mcmeta` (every jar; per-MC `pack_format`)

`pack_format` lookup:
- 1.18.2 → 8
- 1.19.2 → 9
- 1.20.1 → 15
- 1.21.1 → 34
