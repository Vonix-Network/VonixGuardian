package network.vonix.guardian.core.rollback;

import network.vonix.guardian.core.query.QueryFilter;

/**
 * World scope for {@code /vg purge}.
 *
 * <p>A purge covers every world unless the operator named one
 * ({@code r:#nether}, {@code r:#world_the_nether}, {@code r:#global}).
 * The caller's current world is not applied. Silent narrowing of a
 * delete is not acceptable; {@link #label} is what the confirmation prints.</p>
 */
public final class PurgeScope {

    private PurgeScope() {}

    /**
     * @param parsed       the parsed filter; a world selector already on it is kept
     * @param callerWorld  the sender's world, ignored on purpose so a bare purge
     *                     is not limited to wherever the operator is standing
     */
    public static QueryFilter prepare(QueryFilter parsed, String callerWorld) {
        return parsed;
    }

    public static String label(QueryFilter filter) {
        if (filter == null || filter.worldSel() == null || filter.worldSel().global()) {
            return "all worlds";
        }
        String key = filter.worldSel().worldKey();
        if (key == null || key.isBlank()) return "all worlds";
        return key;
    }
}
