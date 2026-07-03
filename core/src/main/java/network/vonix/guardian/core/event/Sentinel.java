package network.vonix.guardian.core.event;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Frozen registry of sentinel actor-name strings used when no real player UUID
 * exists for the source of an event (creepers, lava, pistons, etc.).
 *
 * <p>All values are exactly {@code "#<lowercase>"}. The full list is defined in
 * SHARED-CONTRACTS § 8 and MUST NOT be reordered or renamed without a contract
 * bump.</p>
 */
public final class Sentinel {

    private Sentinel() {}

    public static final String CREEPER        = "#creeper";
    public static final String TNT            = "#tnt";
    public static final String END_CRYSTAL    = "#end_crystal";
    public static final String WITHER_SKULL   = "#wither_skull";
    public static final String FIREBALL       = "#fireball";
    public static final String BED            = "#bed";
    public static final String RESPAWN_ANCHOR = "#respawn_anchor";
    public static final String FIRE           = "#fire";
    public static final String LAVA           = "#lava";
    public static final String WATER          = "#water";
    public static final String PISTON         = "#piston";
    public static final String FALL           = "#fall";
    public static final String DROWN          = "#drown";
    public static final String SUFFOCATE      = "#suffocate";
    public static final String MAGIC          = "#magic";
    public static final String ZOMBIE         = "#zombie";
    public static final String SKELETON       = "#skeleton";
    public static final String ENDERMAN       = "#enderman";
    public static final String EXPLOSION      = "#explosion";
    /** v1.3.1 X4 — actor sentinel for portal-frame block creation. */
    public static final String PORTAL         = "#portal";
    /**
     * v1.3.1 X4 — actor sentinel for {@code /fill} / {@code /setblock} per-block
     * audit rows emitted when the source is a non-player (e.g. command block,
     * automation). Player-invoked commands use the player's UUID/name directly.
     */
    public static final String COMMAND        = "#command";
    /**
     * v1.3.1 X4 — hopper/dropper-driven container transfers with no player
     * attribution (vanilla hopper pull/push). Kept distinct from
     * {@link #PISTON} to keep source classification honest.
     */
    public static final String HOPPER         = "#hopper";
    public static final String UNKNOWN        = "#unknown";

    /** Unmodifiable view of every sentinel string, in declaration order. */
    public static final Set<String> ALL;

    static {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        s.add(CREEPER);
        s.add(TNT);
        s.add(END_CRYSTAL);
        s.add(WITHER_SKULL);
        s.add(FIREBALL);
        s.add(BED);
        s.add(RESPAWN_ANCHOR);
        s.add(FIRE);
        s.add(LAVA);
        s.add(WATER);
        s.add(PISTON);
        s.add(FALL);
        s.add(DROWN);
        s.add(SUFFOCATE);
        s.add(MAGIC);
        s.add(ZOMBIE);
        s.add(SKELETON);
        s.add(ENDERMAN);
        s.add(EXPLOSION);
        s.add(PORTAL);
        s.add(COMMAND);
        s.add(HOPPER);
        s.add(UNKNOWN);
        ALL = Collections.unmodifiableSet(s);
    }

    /**
     * @param name candidate actor-name string (may be {@code null})
     * @return {@code true} iff {@code name} is one of the frozen sentinel strings
     */
    public static boolean isSentinel(String name) {
        return name != null && ALL.contains(name);
    }
}
