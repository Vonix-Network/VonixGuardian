package network.vonix.guardian.core.query;

import network.vonix.guardian.core.action.ActionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable result of parsing a {@code /vg lookup} filter expression.
 *
 * <p>See {@code SHARED-CONTRACTS.md} § 4.1 for the canonical field layout.
 * Every list field is non-null but may be empty; every boxed scalar may be
 * {@code null} to denote "not constrained / default".
 *
 * <p>Default semantics when a token is absent (see § 4.2):
 * <ul>
 *   <li>{@code radius} — caller treats {@code null} as default {@code 10}.</li>
 *   <li>{@code sinceMillis} / {@code untilMillis} — {@code null} means no
 *       temporal bound on that side.</li>
 *   <li>{@code actions} — empty list means "all action types".</li>
 *   <li>{@code rolledBack} — {@code null} means "either"; {@code TRUE} only
 *       rolled-back rows; {@code FALSE} only not-rolled-back rows.</li>
 * </ul>
 *
 * @param users        user selectors from {@code u:} tokens; never {@code null}
 * @param sinceMillis  inclusive lower bound (epoch millis) or {@code null}
 * @param untilMillis  inclusive upper bound (epoch millis) or {@code null}
 * @param radius       block radius; {@code null} = default, {@code -1} = #global
 * @param worldSel     explicit world selector (from {@code r:#world_<k>} /
 *                     {@code r:#global}) or {@code null} to fall through to caller
 * @param centerX      center X coord for radius search; {@code null} for non-positional
 * @param centerY      center Y coord
 * @param centerZ      center Z coord
 * @param actions      action selectors from {@code a:} tokens; empty = all
 * @param include      identifiers from {@code i:} tokens (e.g. {@code minecraft:stone})
 * @param exclude      identifiers from {@code e:} tokens
 * @param rolledBack   SQL-side rollback-state filter; {@code null} = either,
 *                     {@code TRUE} = only rolled-back rows, {@code FALSE} = only
 *                     not-yet-rolled-back rows
 * @param countOnly    {@code #count} flag
 * @param preview      {@code #preview} flag
 * @param verbose      {@code #verbose} flag
 * @param silent       {@code #silent} flag
 */
public record QueryFilter(
    List<UserSel> users,
    Long sinceMillis,
    Long untilMillis,
    Integer radius,
    WorldSel worldSel,
    Integer centerX, Integer centerY, Integer centerZ,
    List<ActionSelect> actions,
    List<String> include,
    List<String> exclude,
    Boolean rolledBack,
    boolean countOnly,
    boolean preview,
    boolean verbose,
    boolean silent
) {

    /** Compact-canonical constructor: defensively copies lists and replaces nulls with empty. */
    public QueryFilter {
        users   = users   == null ? List.of() : List.copyOf(users);
        actions = actions == null ? List.of() : List.copyOf(actions);
        include = include == null ? List.of() : List.copyOf(include);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
    }

    /**
     * @return the canonical "no constraints" filter (empty lists, all nullable
     *         scalars null, all flags false)
     */
    public static QueryFilter empty() {
        return new QueryFilter(
            List.of(), null, null, null, null,
            null, null, null,
            List.of(), List.of(), List.of(),
            null,
            false, false, false, false
        );
    }

    /** @return a fresh mutable builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a copy of this filter with {@link #worldSel} defaulted to the given
     * {@code worldId} when the caller did not specify {@code w:} / {@code r:#world_*}
     * / {@code r:#global}. Used by player-issued lookups so the implicit default
     * matches CoreProtect: the caller's current world. Console-issued lookups
     * should NOT use this — they intentionally see global.
     *
     * <p>If this filter already has any {@link #worldSel} set (either a specific
     * world or {@code global=true}), this returns {@code this} unchanged.
     */
    public QueryFilter withDefaultWorld(String worldId) {
        if (worldSel != null) return this;
        if (worldId == null || worldId.isBlank()) return this;
        return new QueryFilter(
            users, sinceMillis, untilMillis, radius,
            new WorldSel(worldId, false),
            centerX, centerY, centerZ,
            actions, include, exclude,
            rolledBack, countOnly, preview, verbose, silent
        );
    }

    // --- nested value types -------------------------------------------------

    /**
     * A single entry of a {@code u:} token. Exactly one of {@code uuid} or
     * {@code name} is typically set; sentinels (e.g. {@code #creeper}) carry
     * {@code isSentinel=true} and a {@code name} starting with {@code #}.
     *
     * @param uuid        player UUID if known; otherwise {@code null}
     * @param name        player name or sentinel ({@code #creeper}, …)
     * @param isSentinel  {@code true} if {@code name} is a sentinel id
     */
    public record UserSel(UUID uuid, String name, boolean isSentinel) {}

    /**
     * Output of a {@code r:#global} or {@code r:#world_<key>} token.
     *
     * @param worldKey  the explicit world key (e.g. {@code minecraft:the_nether})
     *                  or {@code null} when {@code global} is {@code true}
     * @param global    {@code true} for {@code r:#global} (search across all worlds)
     */
    public record WorldSel(String worldKey, boolean global) {}

    /**
     * One {@code a:} entry. {@link Sign#ANY} means the bare action name was
     * used (e.g. {@code a:block}), while {@link Sign#PLACE_ONLY} /
     * {@link Sign#BREAK_ONLY} reflect an explicit {@code +} or {@code -}.
     *
     * @param type  the resolved action type
     * @param sign  the sign modifier (see {@link Sign})
     */
    public record ActionSelect(ActionType type, Sign sign) {

        /** Sign modifier on an {@code a:} selector. */
        public enum Sign { ANY, PLACE_ONLY, BREAK_ONLY }
    }

    // --- builder ------------------------------------------------------------

    /**
     * Mutable builder for {@link QueryFilter}. Not thread-safe.
     */
    public static final class Builder {
        private final List<UserSel> users = new ArrayList<>();
        private Long sinceMillis;
        private Long untilMillis;
        private Integer radius;
        private WorldSel worldSel;
        private Integer centerX;
        private Integer centerY;
        private Integer centerZ;
        private final List<ActionSelect> actions = new ArrayList<>();
        private final List<String> include = new ArrayList<>();
        private final List<String> exclude = new ArrayList<>();
        private Boolean rolledBack;
        private boolean countOnly;
        private boolean preview;
        private boolean verbose;
        private boolean silent;

        private Builder() {}

        public Builder addUser(UserSel u) { users.add(u); return this; }
        public Builder sinceMillis(Long v) { this.sinceMillis = v; return this; }
        public Builder untilMillis(Long v) { this.untilMillis = v; return this; }
        public Builder radius(Integer v) { this.radius = v; return this; }
        public Builder worldSel(WorldSel v) { this.worldSel = v; return this; }
        public Builder center(Integer x, Integer y, Integer z) {
            this.centerX = x; this.centerY = y; this.centerZ = z; return this;
        }
        public Builder addAction(ActionSelect a) { actions.add(a); return this; }
        public Builder addInclude(String s) { include.add(s); return this; }
        public Builder addExclude(String s) { exclude.add(s); return this; }
        public Builder rolledBack(Boolean v) { this.rolledBack = v; return this; }
        public Builder countOnly(boolean v) { this.countOnly = v; return this; }
        public Builder preview(boolean v)   { this.preview   = v; return this; }
        public Builder verbose(boolean v)   { this.verbose   = v; return this; }
        public Builder silent(boolean v)    { this.silent    = v; return this; }

        public List<UserSel>    users()   { return Collections.unmodifiableList(users); }
        public List<ActionSelect> actions() { return Collections.unmodifiableList(actions); }
        public List<String>     include() { return Collections.unmodifiableList(include); }
        public List<String>     exclude() { return Collections.unmodifiableList(exclude); }
        public Integer          radius()  { return radius; }
        public Long             sinceMillis() { return sinceMillis; }
        public Long             untilMillis() { return untilMillis; }
        public WorldSel         worldSel() { return worldSel; }
        public Integer          centerX() { return centerX; }
        public Integer          centerY() { return centerY; }
        public Integer          centerZ() { return centerZ; }
        public Boolean          rolledBack() { return rolledBack; }
        public boolean          countOnly() { return countOnly; }
        public boolean          preview()   { return preview; }
        public boolean          verbose()   { return verbose; }
        public boolean          silent()    { return silent; }

        /** @return the immutable {@link QueryFilter}. */
        public QueryFilter build() {
            return new QueryFilter(
                users, sinceMillis, untilMillis, radius, worldSel,
                centerX, centerY, centerZ,
                actions, include, exclude,
                rolledBack,
                countOnly, preview, verbose, silent
            );
        }
    }
}
