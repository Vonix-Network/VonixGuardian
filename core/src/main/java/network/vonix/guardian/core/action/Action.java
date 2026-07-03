package network.vonix.guardian.core.action;

import java.util.UUID;

/**
 * Immutable record of a single logged event. All fields are nullable EXCEPT
 * timestamp + type + worldId.
 *
 * <h2>v1.3.1 X1 — NBT fidelity fields</h2>
 *
 * <p>Five additional nullable fields carry Ledger-parity NBT context for
 * block/item/entity rows so {@code /vg rollback} and {@code /vg restore} can
 * put back <em>exactly</em> what was there (waterlogged fence with the right
 * facing property, a named+enchanted Netherite sword, a chest with its
 * contents, a mob with its custom name/attributes). Producers only populate
 * these when {@code storage.persistNbt=true} in the config; the DAO always
 * reads them if the row has them so downgrading the toggle does not lose
 * historical NBT.
 *
 * @param id           DB-assigned row id; -1 before insertion
 * @param timestamp    epoch millis (UTC)
 * @param type         action type
 * @param actorUuid    player UUID if known; null for non-player sources (creeper, lava, etc.)
 * @param actorName    resolved name at time of event ("Notch") or sentinel ("#creeper", "#tnt", "#lava", "#fire", "#water", "#piston", "#fall", "#unknown")
 * @param worldId      world / dimension key as string ("minecraft:overworld", "minecraft:the_nether", ...)
 * @param x            block X coordinate; 0 for non-positional events
 * @param y            block Y coordinate; 0 for non-positional events
 * @param z            block Z coordinate; 0 for non-positional events
 * @param targetId     string ID of the affected thing — block id ("minecraft:stone"), entity type ("minecraft:zombie"), item id, or message body for chat/command/sign
 * @param targetMeta   optional NBT/state snapshot as compact JSON string; null if N/A
 * @param amount       stack count for item/container events; 1 for block events; 0 if N/A
 * @param rolledBack   true if this action has been undone by a rollback
 * @param sourceTag    optional source classifier ("explosion:tnt", "death:fall", "drop:death") or null
 * @param signSide     for SIGN events: "front" / "back" — or {@code null} for non-sign rows and MC versions without a back side
 * @param signDyeColor for SIGN events: dye color name (e.g. {@code "red"}) — or {@code null} if uncolored / unknown / non-sign row
 * @param signWaxed    for SIGN events: {@code true} if waxed, {@code false} if not, {@code null} on MC versions without the waxed flag or non-sign rows
 * @param oldBlockState v1.3.1 X1: pre-change block-state property string
 *                     (e.g. {@code "facing=north,waterlogged=true"}) or
 *                     {@code null} for non-block rows or when
 *                     {@code storage.persistNbt=false}
 * @param newBlockState v1.3.1 X1: post-change block-state property string or
 *                     {@code null} in the same conditions as
 *                     {@code oldBlockState}
 * @param blockEntityNbt v1.3.1 X1: raw block-entity NBT bytes (chest contents,
 *                     spawner data, sign back text, etc.) written via
 *                     {@code net.minecraft.nbt.NbtIo.write}. May be very
 *                     large; the DAO stores it as {@code LONGBLOB} /
 *                     {@code BYTEA} / {@code BLOB}
 * @param itemNbt      v1.3.1 X1: raw item NBT bytes for item-carrying events
 *                     so named / enchanted / damaged items round-trip
 *                     byte-parity with CoreProtect apply semantics
 * @param entityNbt    v1.3.1 X1: raw entity NBT bytes so {@code /vg restore}
 *                     can respawn a mob with its original attributes
 *                     (custom name, tame owner, potion effects, etc.)
 */
public record Action(
    long id,
    long timestamp,
    ActionType type,
    UUID actorUuid,
    String actorName,
    String worldId,
    int x, int y, int z,
    String targetId,
    String targetMeta,
    int amount,
    boolean rolledBack,
    String sourceTag,
    String signSide,
    String signDyeColor,
    Boolean signWaxed,
    String oldBlockState,
    String newBlockState,
    byte[] blockEntityNbt,
    byte[] itemNbt,
    byte[] entityNbt
) {

    /**
     * Legacy 14-arg constructor (pre-schema-v4). Delegates to the canonical
     * constructor with {@code signSide=null, signDyeColor=null, signWaxed=null}
     * and all v1.3.1 NBT fields {@code null}, preserving source compatibility
     * for all pre-v1.1.7 call sites.
     */
    public Action(long id, long timestamp, ActionType type, UUID actorUuid, String actorName,
                  String worldId, int x, int y, int z, String targetId, String targetMeta,
                  int amount, boolean rolledBack, String sourceTag) {
        this(id, timestamp, type, actorUuid, actorName, worldId, x, y, z, targetId, targetMeta,
             amount, rolledBack, sourceTag, null, null, null, null, null, null, null, null);
    }

    /**
     * v1.1.7-era 17-arg constructor including the three sign metadata fields
     * but without v1.3.1 X1's NBT fidelity columns. Delegates to the canonical
     * constructor with the five NBT fields {@code null}. Retained so pre-v1.3.1
     * producers, tests, and third-party API callers keep compiling unchanged.
     */
    public Action(long id, long timestamp, ActionType type, UUID actorUuid, String actorName,
                  String worldId, int x, int y, int z, String targetId, String targetMeta,
                  int amount, boolean rolledBack, String sourceTag,
                  String signSide, String signDyeColor, Boolean signWaxed) {
        this(id, timestamp, type, actorUuid, actorName, worldId, x, y, z, targetId, targetMeta,
             amount, rolledBack, sourceTag, signSide, signDyeColor, signWaxed,
             null, null, null, null, null);
    }

    /**
     * Whether this action's coordinates carry meaning.
     *
     * <p>Chat, commands, session join/leave and username-change events are not
     * tied to a block position; the {@code x/y/z} fields of such records are
     * conventionally zero and should not be used for spatial queries.
     *
     * @return {@code true} if the {@code x/y/z} coordinates of this action are
     *         meaningful, {@code false} for chat/command/session/username events
     */
    public boolean isPositional() {
        return switch (type) {
            case CHAT, COMMAND, SESSION_JOIN, SESSION_LEAVE, USERNAME_CHANGE -> false;
            default -> true;
        };
    }

    /**
     * True when this row carries any of the five v1.3.1 X1 NBT fidelity fields.
     * Producers gate on {@code storage.persistNbt}; the DAO always reads
     * whatever it finds, so this predicate is stable across config toggles.
     *
     * @return {@code true} if at least one NBT column is non-null
     * @since 1.3.1
     */
    public boolean hasNbt() {
        return oldBlockState != null || newBlockState != null
            || blockEntityNbt != null || itemNbt != null || entityNbt != null;
    }
}
