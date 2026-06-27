package network.vonix.guardian.core.action;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Enumeration of every kind of event VonixGuardian can audit.
 *
 * <p>Each constant carries four pieces of stable metadata:
 * <ul>
 *   <li>{@link #id()} — the small integer persisted in the {@code vg_actions.type}
 *       column. These ids are <strong>stable database keys</strong>; the enum
 *       constants must never be reordered or renumbered without a schema migration.</li>
 *   <li>{@link #token()} — the short CLI token used in the {@code /vg lookup a:&lt;token&gt;}
 *       mini-language (see {@code SHARED-CONTRACTS.md} § 4.2).</li>
 *   <li>{@link #category()} — coarse family used by gating + {@code a:&lt;family&gt;}
 *       expansion.</li>
 *   <li>{@link #sign()} — direction (place/break/neutral) used by sign-filtered
 *       lookups ({@code a:+block} vs {@code a:-block}).</li>
 * </ul>
 *
 * <p>Lookup helpers {@link #byId(int)} and {@link #byToken(String)} provide
 * O(1) reverse lookup and throw {@link IllegalArgumentException} on miss.
 */
public enum ActionType {

    // --- core block events (1-2) ---
    BLOCK_PLACE             ( 1, "+block",      Category.BLOCK,     Sign.PLACE),
    BLOCK_BREAK             ( 2, "-block",      Category.BLOCK,     Sign.BREAK),
    // --- player container surface (3-4) ---
    CONTAINER_DEPOSIT       ( 3, "+container",  Category.CONTAINER, Sign.PLACE),
    CONTAINER_WITHDRAW      ( 4, "-container",  Category.CONTAINER, Sign.BREAK),
    // --- item flow (5-6) ---
    ITEM_DROP               ( 5, "-item",       Category.ITEM,      Sign.BREAK),
    ITEM_PICKUP             ( 6, "+item",       Category.ITEM,      Sign.PLACE),
    // --- entity surface (7) ---
    ENTITY_KILL             ( 7, "kill",        Category.ENTITY,    Sign.NEUTRAL),
    // --- environment (8) ---
    EXPLOSION               ( 8, "explosion",   Category.WORLD,     Sign.BREAK),
    // --- communication (9-11) ---
    CHAT                    ( 9, "chat",        Category.MESSAGE,   Sign.NEUTRAL),
    COMMAND                 (10, "command",     Category.MESSAGE,   Sign.NEUTRAL),
    SIGN                    (11, "sign",        Category.MESSAGE,   Sign.NEUTRAL),
    // --- session (12-14) ---
    SESSION_JOIN            (12, "+session",    Category.SESSION,   Sign.PLACE),
    SESSION_LEAVE           (13, "-session",    Category.SESSION,   Sign.BREAK),
    USERNAME_CHANGE         (14, "username",    Category.SESSION,   Sign.NEUTRAL),
    // --- v0.1.0 expansion: vanilla griefing surface (15-26) ---
    BURN                    (15, "burn",        Category.BLOCK,     Sign.BREAK),
    IGNITE                  (16, "ignite",      Category.BLOCK,     Sign.PLACE),
    FADE                    (17, "fade",        Category.BLOCK,     Sign.BREAK),
    FORM                    (18, "form",        Category.BLOCK,     Sign.PLACE),
    SPREAD                  (19, "spread",      Category.BLOCK,     Sign.PLACE),
    DISPENSE                (20, "dispense",    Category.BLOCK,     Sign.NEUTRAL),
    PISTON_EXTEND           (21, "+piston",     Category.BLOCK,     Sign.PLACE),
    PISTON_RETRACT          (22, "-piston",     Category.BLOCK,     Sign.BREAK),
    BUCKET_EMPTY            (23, "+bucket",     Category.BLOCK,     Sign.PLACE),
    BUCKET_FILL             (24, "-bucket",     Category.BLOCK,     Sign.BREAK),
    LEAVES_DECAY            (25, "decay",       Category.BLOCK,     Sign.BREAK),
    /** Mob/dragon/ravager-driven block change. THE modded griefing path. */
    ENTITY_CHANGE_BLOCK     (26, "entityblock", Category.BLOCK,     Sign.NEUTRAL),
    // --- v0.1.0 expansion: player inventory + crafting (27-31) ---
    INVENTORY_DEPOSIT       (27, "+inventory",  Category.CONTAINER, Sign.PLACE),
    INVENTORY_WITHDRAW      (28, "-inventory",  Category.CONTAINER, Sign.BREAK),
    HOPPER_PUSH             (29, "+hopper",     Category.CONTAINER, Sign.PLACE),
    HOPPER_PULL             (30, "-hopper",     Category.CONTAINER, Sign.BREAK),
    ITEM_CRAFT              (31, "craft",       Category.ITEM,      Sign.NEUTRAL),
    // --- v0.1.0 expansion: entities (32-35) ---
    ENTITY_SPAWN            (32, "spawn",       Category.ENTITY,    Sign.PLACE),
    ENTITY_INTERACT         (33, "einteract",   Category.ENTITY,    Sign.NEUTRAL),
    HANGING_PLACE           (34, "+hanging",    Category.ENTITY,    Sign.PLACE),
    HANGING_BREAK           (35, "-hanging",    Category.ENTITY,    Sign.BREAK),
    // --- v0.1.0 expansion: world events (36-38) ---
    STRUCTURE_GROW          (36, "grow",        Category.WORLD,     Sign.PLACE),
    PORTAL_CREATE           (37, "portal",      Category.WORLD,     Sign.PLACE),
    CHUNK_POPULATE          (38, "populate",    Category.WORLD,     Sign.PLACE),
    // --- v0.1.0 expansion: generic interaction (39) ---
    CLICK                   (39, "click",       Category.INTERACT,  Sign.NEUTRAL);

    /** Coarse family classification — drives gating + {@code a:&lt;family&gt;} expansion. */
    public enum Category { BLOCK, CONTAINER, ITEM, ENTITY, WORLD, MESSAGE, SESSION, INTERACT }

    /** Direction marker; used by {@code a:+xxx} vs {@code a:-xxx} sign filtering. */
    public enum Sign { PLACE, BREAK, NEUTRAL }

    private static final Map<Integer, ActionType> BY_ID;
    private static final Map<String, ActionType> BY_TOKEN;
    private static final Map<Category, Set<ActionType>> BY_CATEGORY;

    static {
        Map<Integer, ActionType> byId = new HashMap<>();
        Map<String, ActionType> byToken = new HashMap<>();
        Map<Category, EnumSet<ActionType>> byCat = new HashMap<>();
        for (Category c : Category.values()) {
            byCat.put(c, EnumSet.noneOf(ActionType.class));
        }
        for (ActionType t : values()) {
            byId.put(t.id, t);
            byToken.put(t.token, t);
            byCat.get(t.category).add(t);
        }
        BY_ID = Collections.unmodifiableMap(byId);
        BY_TOKEN = Collections.unmodifiableMap(byToken);
        Map<Category, Set<ActionType>> frozen = new HashMap<>();
        for (Map.Entry<Category, EnumSet<ActionType>> e : byCat.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        BY_CATEGORY = Collections.unmodifiableMap(frozen);
    }

    private final int id;
    private final String token;
    private final Category category;
    private final Sign sign;

    ActionType(int id, String token, Category category, Sign sign) {
        this.id = id;
        this.token = token;
        this.category = category;
        this.sign = sign;
    }

    /**
     * @return the stable database id of this action type
     */
    public int id() {
        return id;
    }

    /**
     * @return the CLI token of this action type
     */
    public String token() {
        return token;
    }

    /**
     * @return the coarse {@link Category} of this action type
     */
    public Category category() {
        return category;
    }

    /**
     * @return the directional {@link Sign} of this action type
     */
    public Sign sign() {
        return sign;
    }

    /**
     * Resolves an {@link ActionType} by its stable database id.
     *
     * @param id the id to look up
     * @return the matching {@link ActionType}
     * @throws IllegalArgumentException if no action type has the given id
     */
    public static ActionType byId(int id) {
        ActionType t = BY_ID.get(id);
        if (t == null) {
            throw new IllegalArgumentException("No ActionType with id " + id);
        }
        return t;
    }

    /**
     * Resolves an {@link ActionType} by its CLI token.
     *
     * @param token the token to look up; must not be {@code null}
     * @return the matching {@link ActionType}
     * @throws IllegalArgumentException if {@code token} is {@code null} or unknown
     */
    public static ActionType byToken(String token) {
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }
        ActionType t = BY_TOKEN.get(token);
        if (t == null) {
            throw new IllegalArgumentException("No ActionType with token '" + token + "'");
        }
        return t;
    }

    /**
     * Returns every {@link ActionType} belonging to the given {@link Category}.
     *
     * <p>Used by the {@code /vg lookup} mini-language to expand {@code a:block},
     * {@code a:container}, etc.</p>
     *
     * @param cat the category to query; must not be {@code null}
     * @return an unmodifiable, possibly-empty set of action types in {@code cat}
     * @throws IllegalArgumentException if {@code cat} is {@code null}
     */
    public static Set<ActionType> family(Category cat) {
        if (cat == null) {
            throw new IllegalArgumentException("cat must not be null");
        }
        return BY_CATEGORY.getOrDefault(cat, Set.of());
    }
}
