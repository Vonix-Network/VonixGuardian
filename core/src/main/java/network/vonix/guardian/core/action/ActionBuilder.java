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
    private String signSide;
    private String signDyeColor;
    private Boolean signWaxed;
    // ---- v1.3.1 X1: NBT fidelity ----
    private String oldBlockState;
    private String newBlockState;
    private byte[] blockEntityNbt;
    private byte[] itemNbt;
    private byte[] entityNbt;

    /** Creates a new empty builder. */
    public ActionBuilder() {
    }

    /**
     * Clear every field back to the same state a freshly-constructed builder has,
     * so a caller (e.g. the per-thread scratch builder in {@link
     * network.vonix.guardian.core.Guardian#seed}) can reuse this instance for the
     * next event without allocating.
     *
     * <p>Intended for the server-thread hot path (v1.3.0 W2): {@link
     * network.vonix.guardian.core.Guardian} keeps one {@code ActionBuilder} per
     * thread in a {@link ThreadLocal} and calls {@code reset()} at the top of
     * every {@code seed(...)} call. The immutable {@link Action} produced by
     * {@link #build()} is still a fresh object per submit — the amortized win is
     * on the mutable builder scaffolding (16 nullable field slots, plus the
     * builder object header itself), which accounted for &gt;40% of the
     * per-submit garbage on the piston/fire/hopper hot paths.</p>
     *
     * @return this builder, cleared
     * @since 1.3.0
     */
    public ActionBuilder reset() {
        this.id = -1L;
        this.timestamp = null;
        this.type = null;
        this.actorUuid = null;
        this.actorName = null;
        this.worldId = null;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.targetId = null;
        this.targetMeta = null;
        this.amount = null;
        this.rolledBack = false;
        this.sourceTag = null;
        this.signSide = null;
        this.signDyeColor = null;
        this.signWaxed = null;
        // v1.3.1 X1: clear NBT byte[] references so the previous submit's
        // (potentially large) block-entity / item / entity payload does not
        // survive across a reused scratch builder. Nulling the reference is
        // enough — the underlying array is either DAO-owned (already batched)
        // or unreachable elsewhere.
        this.oldBlockState = null;
        this.newBlockState = null;
        this.blockEntityNbt = null;
        this.itemNbt = null;
        this.entityNbt = null;
        return this;
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
     * Sets the sign side ({@code "front"} / {@code "back"}) for SIGN events.
     * On MC versions without a back side (1.19.2 / 1.18.2) callers should pass
     * {@code "front"} (or {@code null} for non-sign rows).
     *
     * @param signSide the sign side; may be {@code null}
     * @return this builder
     */
    public ActionBuilder signSide(String signSide) {
        this.signSide = signSide;
        return this;
    }

    /**
     * Sets the sign dye color name for SIGN events (e.g. {@code "red"}).
     *
     * @param signDyeColor the dye color name; may be {@code null}
     * @return this builder
     */
    public ActionBuilder signDyeColor(String signDyeColor) {
        this.signDyeColor = signDyeColor;
        return this;
    }

    /**
     * Sets the waxed flag for SIGN events. Pass {@code null} on MC versions
     * that pre-date the waxed feature (1.19.2 / 1.18.2).
     *
     * @param signWaxed the waxed flag; may be {@code null}
     * @return this builder
     */
    public ActionBuilder signWaxed(Boolean signWaxed) {
        this.signWaxed = signWaxed;
        return this;
    }

    // ------------------------------------------------------------------------
    // v1.3.1 X1 — NBT fidelity setters. All nullable; producers only populate
    // when the operator has opted in to storage.persistNbt=true. Callers pass
    // the byte[] payload by reference; the DAO takes ownership on submit.
    // ------------------------------------------------------------------------

    /**
     * Sets the pre-change block-state property string (Ledger-parity).
     *
     * <p>Format: compact {@code k=v,k=v} produced by the loader-side
     * {@code BlockStateProperties.serialize()} helper. Not JSON — commas are a
     * top-level separator and must not appear inside values.
     *
     * @param oldBlockState property string; may be {@code null}
     * @return this builder
     * @since 1.3.1
     */
    public ActionBuilder oldBlockState(String oldBlockState) {
        this.oldBlockState = oldBlockState;
        return this;
    }

    /**
     * Sets the post-change block-state property string; symmetric counterpart
     * to {@link #oldBlockState(String)}.
     *
     * @param newBlockState property string; may be {@code null}
     * @return this builder
     * @since 1.3.1
     */
    public ActionBuilder newBlockState(String newBlockState) {
        this.newBlockState = newBlockState;
        return this;
    }

    /**
     * Sets the raw block-entity NBT bytes (chest contents, spawner data, sign
     * back text, brewing stand fuel, etc.). Encoded by the loader via
     * {@code net.minecraft.nbt.NbtIo.write(CompoundTag, DataOutputStream)}
     * and decoded on rollback via {@code NbtIo.read(...)}.
     *
     * <p>The DAO takes ownership of the byte[] reference; callers should not
     * mutate it after this call.
     *
     * @param blockEntityNbt raw NBT bytes; may be {@code null}
     * @return this builder
     * @since 1.3.1
     */
    public ActionBuilder blockEntityNbt(byte[] blockEntityNbt) {
        this.blockEntityNbt = blockEntityNbt;
        return this;
    }

    /**
     * Sets the raw item NBT bytes so named / enchanted / damaged items
     * round-trip byte-parity with CoreProtect apply semantics.
     *
     * @param itemNbt raw NBT bytes; may be {@code null}
     * @return this builder
     * @since 1.3.1
     */
    public ActionBuilder itemNbt(byte[] itemNbt) {
        this.itemNbt = itemNbt;
        return this;
    }

    /**
     * Sets the raw entity NBT bytes so {@code /vg restore} can respawn a mob
     * with its original attributes (custom name, tame owner, potion effects,
     * equipment, etc.).
     *
     * @param entityNbt raw NBT bytes; may be {@code null}
     * @return this builder
     * @since 1.3.1
     */
    public ActionBuilder entityNbt(byte[] entityNbt) {
        this.entityNbt = entityNbt;
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
            sourceTag,
            signSide,
            signDyeColor,
            signWaxed,
            oldBlockState,
            newBlockState,
            blockEntityNbt,
            itemNbt,
            entityNbt
        );
    }
}
