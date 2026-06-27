package network.vonix.guardian.core.action;

import java.util.Objects;
import java.util.UUID;

/**
 * Mutable fluent builder that produces an immutable {@link Action}.
 *
 * <p>Defaults applied at {@link #build()} time when the corresponding setter
 * was never invoked:
 * <ul>
 *   <li>{@code id = -1L} (the DAO will assign on insert)</li>
 *   <li>{@code timestamp = System.currentTimeMillis()}</li>
 *   <li>{@code amount = 1}</li>
 *   <li>{@code rolledBack = false}</li>
 *   <li>{@code actorName = "#unknown"} if neither {@code actorName} nor
 *       {@code actorUuid} was set</li>
 * </ul>
 *
 * <p>Required (non-null) inputs at build time: {@link #type(ActionType)} and
 * {@link #worldId(String)}. Builders are not thread-safe; create one per event.
 */
public final class ActionBuilder {

    /** Sentinel actor name used when no UUID and no explicit name are provided. */
    public static final String UNKNOWN_ACTOR_NAME = "#unknown";

    private long id = -1L;
    private Long timestamp;
    private ActionType type;
    private UUID actorUuid;
    private String actorName;
    private String worldId;
    private int x;
    private int y;
    private int z;
    private String targetId;
    private String targetMeta;
    private Integer amount;
    private boolean rolledBack = false;
    private String sourceTag;

    /** Creates a new empty builder. */
    public ActionBuilder() {
    }

    /**
     * Sets the DB-assigned row id.
     *
     * @param id row id; {@code -1L} indicates "not yet persisted"
     * @return this builder
     */
    public ActionBuilder id(long id) {
        this.id = id;
        return this;
    }

    /**
     * Sets the event timestamp (epoch millis, UTC).
     *
     * @param timestamp epoch millis
     * @return this builder
     */
    public ActionBuilder timestamp(long timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    /**
     * Sets the action type. Required.
     *
     * @param type the action type; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public ActionBuilder type(ActionType type) {
        this.type = Objects.requireNonNull(type, "type");
        return this;
    }

    /**
     * Sets the actor UUID for player-sourced events.
     *
     * @param actorUuid the player UUID, or {@code null} for non-player sources
     * @return this builder
     */
    public ActionBuilder actorUuid(UUID actorUuid) {
        this.actorUuid = actorUuid;
        return this;
    }

    /**
     * Sets the resolved actor name (a player name or a sentinel like {@code "#creeper"}).
     *
     * @param actorName the actor name; may be {@code null} to fall back to the
     *                  default behaviour described in the class Javadoc
     * @return this builder
     */
    public ActionBuilder actorName(String actorName) {
        this.actorName = actorName;
        return this;
    }

    /**
     * Sets the world / dimension key (e.g. {@code "minecraft:overworld"}). Required.
     *
     * @param worldId the world key; must not be {@code null}
     * @return this builder
     * @throws NullPointerException if {@code worldId} is {@code null}
     */
    public ActionBuilder worldId(String worldId) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        return this;
    }

    /**
     * Sets the block coordinates.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return this builder
     */
    public ActionBuilder position(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /**
     * Sets the X coordinate component.
     *
     * @param x block X
     * @return this builder
     */
    public ActionBuilder x(int x) {
        this.x = x;
        return this;
    }

    /**
     * Sets the Y coordinate component.
     *
     * @param y block Y
     * @return this builder
     */
    public ActionBuilder y(int y) {
        this.y = y;
        return this;
    }

    /**
     * Sets the Z coordinate component.
     *
     * @param z block Z
     * @return this builder
     */
    public ActionBuilder z(int z) {
        this.z = z;
        return this;
    }

    /**
     * Sets the target identifier (block id, entity type, item id, or message body).
     *
     * @param targetId the target id; may be {@code null}
     * @return this builder
     */
    public ActionBuilder targetId(String targetId) {
        this.targetId = targetId;
        return this;
    }

    /**
     * Sets the optional NBT/state snapshot as a compact JSON string.
     *
     * @param targetMeta the meta JSON; may be {@code null}
     * @return this builder
     */
    public ActionBuilder targetMeta(String targetMeta) {
        this.targetMeta = targetMeta;
        return this;
    }

    /**
     * Sets the amount (stack count) associated with the event.
     *
     * @param amount the amount
     * @return this builder
     */
    public ActionBuilder amount(int amount) {
        this.amount = amount;
        return this;
    }

    /**
     * Sets the rolled-back flag.
     *
     * @param rolledBack {@code true} if the action has been undone by a rollback
     * @return this builder
     */
    public ActionBuilder rolledBack(boolean rolledBack) {
        this.rolledBack = rolledBack;
        return this;
    }

    /**
     * Sets the optional source classifier tag (e.g. {@code "explosion:tnt"}).
     *
     * @param sourceTag the source tag; may be {@code null}
     * @return this builder
     */
    public ActionBuilder sourceTag(String sourceTag) {
        this.sourceTag = sourceTag;
        return this;
    }

    /**
     * Builds the immutable {@link Action} from the current builder state, applying
     * defaults as described in the class Javadoc.
     *
     * @return a new immutable {@link Action}
     * @throws IllegalStateException if a required field ({@code type} or
     *                               {@code worldId}) was never set
     */
    public Action build() {
        if (type == null) {
            throw new IllegalStateException("ActionBuilder: type is required");
        }
        if (worldId == null) {
            throw new IllegalStateException("ActionBuilder: worldId is required");
        }
        long ts = (timestamp != null) ? timestamp : System.currentTimeMillis();
        int amt = (amount != null) ? amount : 1;
        String name = actorName;
        if (name == null && actorUuid == null) {
            name = UNKNOWN_ACTOR_NAME;
        }
        return new Action(
            id,
            ts,
            type,
            actorUuid,
            name,
            worldId,
            x, y, z,
            targetId,
            targetMeta,
            amt,
            rolledBack,
            sourceTag
        );
    }
}
