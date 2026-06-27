package network.vonix.guardian.core.action;

import java.util.UUID;

/**
 * Immutable record of a single logged event. All fields are nullable EXCEPT
 * timestamp + type + worldId.
 *
 * @param id          DB-assigned row id; -1 before insertion
 * @param timestamp   epoch millis (UTC)
 * @param type        action type
 * @param actorUuid   player UUID if known; null for non-player sources (creeper, lava, etc.)
 * @param actorName   resolved name at time of event ("Notch") or sentinel ("#creeper", "#tnt", "#lava", "#fire", "#water", "#piston", "#fall", "#unknown")
 * @param worldId     world / dimension key as string ("minecraft:overworld", "minecraft:the_nether", ...)
 * @param x           block X coordinate; 0 for non-positional events
 * @param y           block Y coordinate; 0 for non-positional events
 * @param z           block Z coordinate; 0 for non-positional events
 * @param targetId    string ID of the affected thing — block id ("minecraft:stone"), entity type ("minecraft:zombie"), item id, or message body for chat/command/sign
 * @param targetMeta  optional NBT/state snapshot as compact JSON string; null if N/A
 * @param amount      stack count for item/container events; 1 for block events; 0 if N/A
 * @param rolledBack  true if this action has been undone by a rollback
 * @param sourceTag   optional source classifier ("explosion:tnt", "death:fall", "drop:death") or null
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
    String sourceTag
) {

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
}
