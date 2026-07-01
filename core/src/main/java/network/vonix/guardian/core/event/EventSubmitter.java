package network.vonix.guardian.core.event;

import network.vonix.guardian.core.action.Action;

import java.util.UUID;

/**
 * Loader-facing event submission surface.
 *
 * <p>Loader modules (fabric/forge/neoforge per MC version) hold a single
 * {@code EventSubmitter} instance handed to them by the {@code Guardian}
 * facade. Calling any of the {@code submitXxx} convenience methods builds the
 * canonical {@link Action} and forwards to {@link #submit(Action)}, which is
 * responsible for:
 * <ol>
 *   <li>filtering through {@link EventGate} (per-type toggles + blacklists),</li>
 *   <li>enqueueing onto the async write queue, and</li>
 *   <li>writing to the JSON-lines audit log file (if enabled).</li>
 * </ol>
 *
 * <p>The concrete implementation lives on the {@code Guardian} facade; this
 * interface exists so loaders can be unit-tested with a no-op or recording
 * fake without dragging in the storage / queue stack.</p>
 *
 * <p>Parameter conventions (mirroring SHARED-CONTRACTS § 8):
 * <ul>
 *   <li>{@code actorUuid} — player UUID; {@code null} for synthetic sources.</li>
 *   <li>{@code actorName} — resolved player name, or a {@link Sentinel} string
 *       (e.g. {@link Sentinel#CREEPER}) when {@code actorUuid} is {@code null}.</li>
 *   <li>{@code worldId} — dimension key, e.g. {@code "minecraft:overworld"}.</li>
 *   <li>{@code x/y/z} — block coords; pass {@code 0,0,0} for non-positional events.</li>
 *   <li>{@code targetId} — block id, entity type, item id, or message body.</li>
 *   <li>{@code amount} — stack size / container delta magnitude; signed for
 *       container changes via the dedicated overload.</li>
 *   <li>{@code sourceTag} — optional classifier (e.g. {@code "explosion:tnt"}); may be {@code null}.</li>
 * </ul>
 */
public interface EventSubmitter {

    /**
     * Primary entry point. Gates, then queues + writes the audit log line.
     *
     * @param a fully-built immutable action; must not be {@code null}
     */
    void submit(Action a);

    /**
     * Convenience for {@code BlockBreakEvent}.
     *
     * @param actorUuid player UUID, or {@code null} for synthetic sources
     * @param actorName resolved player name or {@link Sentinel} string
     * @param worldId   dimension key
     * @param x         block x
     * @param y         block y
     * @param z         block z
     * @param blockId   broken block registry id
     * @param sourceTag optional classifier or {@code null}
     */
    void submitBlockBreak(UUID actorUuid, String actorName, String worldId,
                          int x, int y, int z, String blockId, String sourceTag);

    /**
     * Convenience for {@code BlockPlaceEvent}.
     *
     * @param actorUuid player UUID, or {@code null} for synthetic sources (e.g. {@link Sentinel#PISTON})
     * @param actorName resolved player name or {@link Sentinel} string
     * @param worldId   dimension key
     * @param x         block x
     * @param y         block y
     * @param z         block z
     * @param blockId   placed block registry id
     * @param sourceTag optional classifier or {@code null}
     */
    void submitBlockPlace(UUID actorUuid, String actorName, String worldId,
                          int x, int y, int z, String blockId, String sourceTag);

    /**
     * Convenience for container slot changes. The sign of {@code delta} drives
     * the action type: positive ⇒ {@code CONTAINER_DEPOSIT}, negative ⇒
     * {@code CONTAINER_WITHDRAW}. {@code delta == 0} is a no-op and must be
     * ignored by the implementation.
     *
     * @param actorUuid player UUID
     * @param actorName resolved player name
     * @param worldId   dimension key
     * @param x         container block x
     * @param y         container block y
     * @param z         container block z
     * @param itemId    item registry id
     * @param delta     signed slot delta — sign picks deposit vs withdraw, magnitude is stored as amount
     * @param sourceTag optional classifier or {@code null}
     */
    void submitContainerChange(UUID actorUuid, String actorName, String worldId,
                               int x, int y, int z, String itemId, int delta, String sourceTag);

    /**
     * Convenience for {@code ItemTossEvent}.
     *
     * @param actorUuid player UUID
     * @param actorName resolved player name
     * @param worldId   dimension key
     * @param x         drop position x
     * @param y         drop position y
     * @param z         drop position z
     * @param itemId    item registry id
     * @param amount    stack size
     * @param sourceTag optional classifier (e.g. {@code "drop:death"}) or {@code null}
     */
    void submitItemDrop(UUID actorUuid, String actorName, String worldId,
                        int x, int y, int z, String itemId, int amount, String sourceTag);

    /**
     * Convenience for {@code ItemPickupEvent}.
     *
     * @param actorUuid player UUID
     * @param actorName resolved player name
     * @param worldId   dimension key
     * @param x         pickup position x
     * @param y         pickup position y
     * @param z         pickup position z
     * @param itemId    item registry id
     * @param amount    stack size
     * @param sourceTag optional classifier or {@code null}
     */
    void submitItemPickup(UUID actorUuid, String actorName, String worldId,
                          int x, int y, int z, String itemId, int amount, String sourceTag);

    /**
     * Convenience for {@code LivingDeathEvent}.
     *
     * @param actorUuid killer player UUID, or {@code null} for a sentinel source
     * @param actorName killer player name or {@link Sentinel} string
     * @param worldId   dimension key
     * @param x         victim x
     * @param y         victim y
     * @param z         victim z
     * @param entityType victim entity-type registry id
     * @param sourceTag optional classifier (e.g. {@code "death:fall"}) or {@code null}
     */
    void submitEntityKill(UUID actorUuid, String actorName, String worldId,
                          int x, int y, int z, String entityType, String sourceTag);

    /**
     * Convenience for {@code ExplosionDetonate}.
     *
     * @param actorUuid    source player UUID if attributable, else {@code null}
     * @param actorName    {@link Sentinel} string for the source (e.g. {@link Sentinel#CREEPER})
     * @param worldId      dimension key
     * @param x            blast origin x
     * @param y            blast origin y
     * @param z            blast origin z
     * @param affectedJoined comma-joined affected-block list (truncated to 4 KiB by the loader)
     * @param sourceTag    optional classifier (e.g. {@code "explosion:tnt"}) or {@code null}
     */
    void submitExplosion(UUID actorUuid, String actorName, String worldId,
                         int x, int y, int z, String affectedJoined, String sourceTag);

    /**
     * Convenience for {@code ServerChatEvent}. Coords are ignored; pass {@code 0,0,0}.
     *
     * @param actorUuid speaker player UUID
     * @param actorName speaker player name
     * @param worldId   dimension key the player was in when speaking
     * @param message   raw chat body
     */
    void submitChat(UUID actorUuid, String actorName, String worldId, String message);

    /**
     * Convenience for {@code CommandEvent}. Coords are ignored; pass {@code 0,0,0}.
     *
     * @param actorUuid sender player UUID
     * @param actorName sender player name
     * @param worldId   dimension key the player was in when running the command
     * @param command   full command including leading slash
     */
    void submitCommand(UUID actorUuid, String actorName, String worldId, String command);

    /**
     * Convenience for {@code SignChangeEvent} (legacy overload, no metadata).
     *
     * <p>Equivalent to
     * {@link #submitSign(UUID, String, String, int, int, int, String, String, String, Boolean)}
     * with {@code side=null, dyeColor=null, waxed=null}. Kept for source
     * compatibility with pre-v1.1.7 loader integrations.
     *
     * @param actorUuid editing player UUID
     * @param actorName editing player name
     * @param worldId   dimension key
     * @param x         sign x
     * @param y         sign y
     * @param z         sign z
     * @param joinedLines sign text with {@code "\n"} separator
     */
    void submitSign(UUID actorUuid, String actorName, String worldId,
                    int x, int y, int z, String joinedLines);

    /**
     * Convenience for {@code SignChangeEvent} with CoreProtect-v24-compatible
     * side / dye-color / waxed metadata.
     *
     * @param actorUuid editing player UUID
     * @param actorName editing player name
     * @param worldId   dimension key
     * @param x         sign x
     * @param y         sign y
     * @param z         sign z
     * @param joinedLines sign text with {@code "\n"} separator
     * @param side      {@code "front"} / {@code "back"} — or {@code null} if unavailable
     * @param dyeColor  dye color name (e.g. {@code "red"}) — or {@code null}
     * @param waxed     {@code true} if the sign is waxed, {@code false} if not, {@code null} on MC &lt; 1.20
     */
    default void submitSign(UUID actorUuid, String actorName, String worldId,
                            int x, int y, int z, String joinedLines,
                            String side, String dyeColor, Boolean waxed) {
        // Default delegates to the legacy overload so implementations that
        // haven't opted in still work. Real implementations override.
        submitSign(actorUuid, actorName, worldId, x, y, z, joinedLines);
    }

    /**
     * Convenience for {@code PlayerJoinEvent}. Coords are ignored; pass {@code 0,0,0}.
     *
     * @param actorUuid joining player UUID
     * @param actorName joining player name
     * @param worldId   dimension key at spawn
     * @param ipOrHash  client IP, or its hash if {@code config.privacy.hashIps} is set
     */
    void submitSessionJoin(UUID actorUuid, String actorName, String worldId, String ipOrHash);

    /**
     * Convenience for {@code PlayerQuitEvent}. Coords are ignored; pass {@code 0,0,0}.
     *
     * @param actorUuid quitting player UUID
     * @param actorName quitting player name
     * @param worldId   dimension key at quit
     * @param reason    quit reason string
     */
    void submitSessionLeave(UUID actorUuid, String actorName, String worldId, String reason);

    /**
     * Convenience for {@code PlayerProfileChange}.
     *
     * @param actorUuid player UUID (stable across rename)
     * @param newName   the new player name
     * @param worldId   dimension key at time of detection
     * @param oldName   the previous player name
     */
    void submitUsernameChange(UUID actorUuid, String newName, String worldId, String oldName);

    // ---- v0.1.0 expansion: vanilla griefing block events --------------

    /** Convenience for {@code BlockBurnEvent}. */
    void submitBurn(UUID actorUuid, String actorName, String worldId,
                    int x, int y, int z, String blockId, String sourceTag);

    /** Convenience for {@code BlockIgniteEvent}. */
    void submitIgnite(UUID actorUuid, String actorName, String worldId,
                      int x, int y, int z, String blockId, String sourceTag);

    /** Convenience for {@code BlockFadeEvent}. */
    void submitFade(UUID actorUuid, String actorName, String worldId,
                    int x, int y, int z, String blockId, String sourceTag);

    /** Convenience for {@code BlockFormEvent}. */
    void submitForm(UUID actorUuid, String actorName, String worldId,
                    int x, int y, int z, String blockId, String sourceTag);

    /** Convenience for {@code BlockSpreadEvent}. */
    void submitSpread(UUID actorUuid, String actorName, String worldId,
                      int x, int y, int z, String blockId, String sourceTag);

    /** Convenience for {@code BlockDispenseEvent}. */
    void submitDispense(UUID actorUuid, String actorName, String worldId,
                        int x, int y, int z, String itemId, String sourceTag);

    /** Convenience for {@code BlockPistonExtendEvent}. */
    void submitPistonExtend(UUID actorUuid, String actorName, String worldId,
                            int x, int y, int z, String blockId, String sourceTag);

    /** Convenience for {@code BlockPistonRetractEvent}. */
    void submitPistonRetract(UUID actorUuid, String actorName, String worldId,
                             int x, int y, int z, String blockId, String sourceTag);

    /** Convenience for {@code PlayerBucketEmptyEvent}. */
    void submitBucketEmpty(UUID actorUuid, String actorName, String worldId,
                           int x, int y, int z, String fluidOrBlockId, String sourceTag);

    /** Convenience for {@code PlayerBucketFillEvent}. */
    void submitBucketFill(UUID actorUuid, String actorName, String worldId,
                          int x, int y, int z, String fluidOrBlockId, String sourceTag);

    /** Convenience for {@code LeavesDecayEvent}. */
    void submitLeavesDecay(UUID actorUuid, String actorName, String worldId,
                           int x, int y, int z, String blockId, String sourceTag);

    /**
     * Convenience for {@code EntityChangeBlockEvent} — THE modded griefing path
     * (ravager, dragon, modded dragons such as Skrills, etc.).
     *
     * @param actorUuid   typically {@code null} (mob source)
     * @param actorName   entity-type sentinel string, e.g. {@code "#mob:minecraft:ravager"}
     *                    or {@code "#mob:isleofberk:skrill"}
     * @param worldId     dimension key
     * @param x           block x
     * @param y           block y
     * @param z           block z
     * @param oldBlockId  block id before mutation
     * @param newBlockId  block id after mutation
     * @param sourceTag   optional classifier or {@code null}
     */
    void submitEntityChangeBlock(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z,
                                 String oldBlockId, String newBlockId, String sourceTag);

    // ---- v0.1.0 expansion: inventory + crafting ------------------------

    /** Convenience for player-inventory deposit (slot delta &gt; 0). */
    void submitInventoryDeposit(UUID actorUuid, String actorName, String worldId,
                                int x, int y, int z, String itemId, int amount, String sourceTag);

    /** Convenience for player-inventory withdraw (slot delta &lt; 0). */
    void submitInventoryWithdraw(UUID actorUuid, String actorName, String worldId,
                                 int x, int y, int z, String itemId, int amount, String sourceTag);

    /** Convenience for hopper push (item moved into a container). */
    void submitHopperPush(UUID actorUuid, String actorName, String worldId,
                          int x, int y, int z, String itemId, int amount, String sourceTag);

    /** Convenience for hopper pull (item moved out of a container). */
    void submitHopperPull(UUID actorUuid, String actorName, String worldId,
                          int x, int y, int z, String itemId, int amount, String sourceTag);

    /** Convenience for {@code CraftItemEvent}. */
    void submitItemCraft(UUID actorUuid, String actorName, String worldId,
                         int x, int y, int z, String itemId, int amount, String sourceTag);

    // ---- v0.1.0 expansion: entities ------------------------------------

    /** Convenience for {@code EntitySpawnEvent}. */
    void submitEntitySpawn(UUID actorUuid, String actorName, String worldId,
                           int x, int y, int z, String entityType, String sourceTag);

    /** Convenience for {@code PlayerInteractEntityEvent}. */
    void submitEntityInteract(UUID actorUuid, String actorName, String worldId,
                              int x, int y, int z, String entityType, String sourceTag);

    /** Convenience for {@code HangingPlaceEvent} (item frame, painting, etc.). */
    void submitHangingPlace(UUID actorUuid, String actorName, String worldId,
                            int x, int y, int z, String entityType, String sourceTag);

    /** Convenience for {@code HangingBreakEvent}. */
    void submitHangingBreak(UUID actorUuid, String actorName, String worldId,
                            int x, int y, int z, String entityType, String sourceTag);

    // ---- v0.1.0 expansion: world events --------------------------------

    /** Convenience for {@code StructureGrowEvent} (tree/sapling/mushroom growth). */
    void submitStructureGrow(UUID actorUuid, String actorName, String worldId,
                             int x, int y, int z, String structureId, String sourceTag);

    // ---- v0.1.0 expansion: generic interaction -------------------------

    /** Convenience for {@code PlayerInteractEvent} (buttons, levers, doors, plates). */
    void submitClick(UUID actorUuid, String actorName, String worldId,
                     int x, int y, int z, String targetId, String sourceTag);
}
