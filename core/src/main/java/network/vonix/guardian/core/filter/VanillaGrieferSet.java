package network.vonix.guardian.core.filter;

import java.util.List;
import java.util.Set;

/**
 * Vanilla-Minecraft-entity whitelist for {@code LivingDestroyBlockEvent} attribution.
 *
 * <p><strong>Why this exists.</strong> Forge's {@code LivingDestroyBlockEvent} is a
 * <em>prospective query</em> event — Forge fires it as a permission check
 * ("can this mob destroy this block?") on every mob tick, on every block their
 * collision box passes over, even when no destruction actually happens. On modded
 * packs like HTTYD (Isle of Berk + Dragons of the Cosmos + 300+ dragon variants),
 * a single flying dragon fires this event 200,000+ times per second. This is the
 * root cause of the {@code AsyncWriteQueue full} floods on Berk.
 *
 * <p>CoreProtect solves the same problem in Bukkit by only listening to
 * {@code EntityChangeBlockEvent} (which fires on genuine state changes, not
 * per-tick queries) AND restricting the handler to a hardcoded list of ~10
 * vanilla mob classes. Guardian cannot safely port the first half on
 * Forge/NeoForge because {@code LivingDestroyBlockEvent} is prospective and
 * can flood even for nominally vanilla-style entity types on modded packs.
 * Therefore the default runtime policy is stricter than this constant: fail
 * closed unless the operator explicitly adds entity keys to config.
 *
 * <p>Matches the CoreProtect {@code EntityChangeBlockListener} whitelist as of
 * CP-23.2 (2025). Admins who want to audit modded mob griefing can extend the
 * set at runtime via {@code actions.entityChangeAllowlist} in config.
 *
 * @since 1.1.5
 */
public final class VanillaGrieferSet {

    /** Sentinel prefix produced by {@code EntitySentinel.of(...)} on the cell layer. */
    private static final String MOB_SENTINEL_PREFIX = "#mob:";

    /**
     * CoreProtect's hardcoded whitelist, ported. Each entry is a fully qualified
     * MC registry key {@code minecraft:<path>} that corresponds to a vanilla
     * entity class known to genuinely change block state via AI paths.
     *
     * <p><strong>Removed 1.1.6:</strong> {@code minecraft:wind_charge} and
     * {@code minecraft:breeze_wind_charge}. Both are {@code Projectile}, not
     * {@code LivingEntity}, so they never satisfy {@code LivingDestroyBlockEvent}
     * dispatch and their presence here was dead code (WAVE-AUDIT-1.1.5 A5).
     */
    public static final Set<String> DEFAULT_ALLOWLIST = Set.of(
        "minecraft:enderman",         // picks up blocks
        "minecraft:ender_dragon",     // crushes terrain
        "minecraft:wither",           // destroys blocks on hit
        "minecraft:ravager",          // destroys crops/leaves
        "minecraft:silverfish",       // emerges from infested stone
        "minecraft:turtle",           // lays eggs
        "minecraft:fox",              // picks up items (edge case)
        "minecraft:zombie",           // breaks doors (breaking event only)
        "minecraft:falling_block"     // gravity conversions
    );

    private VanillaGrieferSet() {}

    /**
     * Strip the {@code "#mob:"} sentinel prefix produced by the cell-side
     * {@code EntitySentinel.of(...)} helper, yielding a bare registry key
     * suitable for {@link #shouldRecord} / {@link #DEFAULT_ALLOWLIST} lookup.
     *
     * <p>Centralised here in 1.1.6 (WAVE-AUDIT-1.1.5 A3): previously duplicated
     * verbatim in all four cell {@code Events.java} files.
     *
     * @param sentinel a sentinel like {@code "#mob:minecraft:creeper"}, or any
     *                 other string; may be {@code null}
     * @return the bare registry key (e.g. {@code "minecraft:creeper"}) if the
     *         input starts with {@code "#mob:"}; otherwise {@code null}
     */
    public static String stripMobPrefix(String sentinel) {
        if (sentinel == null || !sentinel.startsWith(MOB_SENTINEL_PREFIX)) return null;
        return sentinel.substring(MOB_SENTINEL_PREFIX.length());
    }

    /**
     * Decide whether an entity's block-change should be recorded, given the
     * configured allowlist plus a "log all" opt-in escape hatch.
     *
     * @param entityRegistryKey  entity type registry key, e.g. {@code "minecraft:ravager"}
     *                           or {@code "isleofberk:night_fury"}. Case-sensitive.
     *                           Empty or null → return false.
     * @param configAllowlist    admin-configured additions (e.g.
     *                           {@code ["iceandfire:fire_dragon"]}); may be null or empty.
     * @param logAllEntities     if {@code true}, all entities are recorded regardless
     *                           of allowlist. Only enable if you know what you're doing —
     *                           this restores the pre-1.1.5 flood behavior on modded packs.
     * @return {@code true} if the event should be forwarded to the queue
     */
    public static boolean shouldRecord(String entityRegistryKey,
                                       List<String> configAllowlist,
                                       boolean logAllEntities) {
        if (logAllEntities) return true;
        if (entityRegistryKey == null || entityRegistryKey.isEmpty()) return false;

        // Forge/NeoForge LivingDestroyBlockEvent is a prospective permission-check event,
        // not a guaranteed block-state-change event like Bukkit's EntityChangeBlockEvent.
        // Even vanilla mob types can fire it at extreme rates on modded packs (Berk/HTTYD
        // proved 5M+ queued ENTITY_CHANGE_BLOCK actions in minutes). Therefore this path
        // must be fail-closed by default: record only explicit operator opt-ins.
        //
        // v1.4.0: allowlist entries support three shapes so an operator can opt in a
        // whole dragon mod at once without enumerating hundreds of variant ids:
        //   - exact:      "isleofberk:night_fury"       matches only that key
        //   - ns wildcard "isleofberk:*"                matches every isleofberk entity
        //   - bare ns     "isleofberk"                  same as "isleofberk:*"
        // The prospective-event flood is absorbed downstream by
        // EntityBlockChangeCoalescer (default 500ms/8192), so whitelisting an entire
        // namespace stays queue-safe.
        if (configAllowlist != null) {
            for (String entry : configAllowlist) {
                if (matches(entry, entityRegistryKey)) return true;
            }
        }

        return false;
    }

    /**
     * Match a single allowlist {@code entry} against a concrete
     * {@code entityRegistryKey}. Supports exact keys, {@code ns:*} wildcards, and
     * bare {@code ns} namespaces. Case-sensitive; whitespace is trimmed.
     *
     * @param entry             an allowlist entry; {@code null}/blank never matches
     * @param entityRegistryKey the concrete {@code ns:path} key under test
     * @return {@code true} if {@code entry} admits {@code entityRegistryKey}
     */
    public static boolean matches(String entry, String entityRegistryKey) {
        if (entry == null || entityRegistryKey == null) return false;
        String e = entry.trim();
        if (e.isEmpty()) return false;

        // Exact registry key.
        if (e.equals(entityRegistryKey)) return true;

        int colon = entityRegistryKey.indexOf(':');
        String keyNs = colon >= 0 ? entityRegistryKey.substring(0, colon) : entityRegistryKey;

        // Namespace wildcard: "ns:*".
        if (e.endsWith(":*")) {
            String ns = e.substring(0, e.length() - 2);
            return !ns.isEmpty() && ns.equals(keyNs);
        }

        // Bare namespace: "ns" (no colon) is treated as "ns:*". Guarded so a bare
        // "minecraft" doesn't silently re-open the flood the default set closes —
        // it only matches when the operator wrote a colonless namespace intentionally.
        if (e.indexOf(':') < 0) {
            return e.equals(keyNs);
        }

        return false;
    }
}
