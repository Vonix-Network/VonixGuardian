package network.vonix.guardian.core.action;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Enumeration of every kind of event VonixGuardian can audit.
 *
 * <p>Each constant carries two pieces of stable metadata:
 * <ul>
 *   <li>{@link #id()} — the small integer persisted in the {@code vg_actions.type}
 *       column. These ids are <strong>stable database keys</strong>; the enum
 *       constants must never be reordered or renumbered without a schema migration.</li>
 *   <li>{@link #token()} — the short CLI token used in the {@code /vg lookup a:&lt;token&gt;}
 *       mini-language (see {@code SHARED-CONTRACTS.md} § 4.2).</li>
 * </ul>
 *
 * <p>Lookup helpers {@link #byId(int)} and {@link #byToken(String)} provide
 * O(1) reverse lookup and throw {@link IllegalArgumentException} on miss.
 */
public enum ActionType {

    /** Block placed by a player or pseudo-source (e.g. piston). */
    BLOCK_PLACE(1, "+block"),
    /** Block broken by a player or pseudo-source. */
    BLOCK_BREAK(2, "-block"),
    /** Item deposited into a container (chest, barrel, hopper, …). */
    CONTAINER_DEPOSIT(3, "+container"),
    /** Item withdrawn from a container. */
    CONTAINER_WITHDRAW(4, "-container"),
    /** Item entity dropped into the world. */
    ITEM_DROP(5, "-item"),
    /** Item entity picked up. */
    ITEM_PICKUP(6, "+item"),
    /** Living entity killed. */
    ENTITY_KILL(7, "kill"),
    /** Explosion event affecting blocks/entities. */
    EXPLOSION(8, "explosion"),
    /** Public chat message. */
    CHAT(9, "chat"),
    /** Slash command executed by a player. */
    COMMAND(10, "command"),
    /** Sign edited. */
    SIGN(11, "sign"),
    /** Player joined / session started. */
    SESSION_JOIN(12, "+session"),
    /** Player left / session ended. */
    SESSION_LEAVE(13, "-session"),
    /** Player username changed. */
    USERNAME_CHANGE(14, "username");

    private static final Map<Integer, ActionType> BY_ID;
    private static final Map<String, ActionType> BY_TOKEN;

    static {
        Map<Integer, ActionType> byId = new HashMap<>();
        Map<String, ActionType> byToken = new HashMap<>();
        for (ActionType t : values()) {
            byId.put(t.id, t);
            byToken.put(t.token, t);
        }
        BY_ID = Collections.unmodifiableMap(byId);
        BY_TOKEN = Collections.unmodifiableMap(byToken);
    }

    private final int id;
    private final String token;

    ActionType(int id, String token) {
        this.id = id;
        this.token = token;
    }

    /**
     * Returns the stable integer id persisted in the database.
     *
     * @return the database id of this action type
     */
    public int id() {
        return id;
    }

    /**
     * Returns the CLI token used in the {@code /vg lookup} mini-language.
     *
     * @return the token of this action type
     */
    public String token() {
        return token;
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
}
