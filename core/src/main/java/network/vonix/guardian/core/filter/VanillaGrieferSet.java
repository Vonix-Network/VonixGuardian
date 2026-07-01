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
 * vanilla mob classes. Guardian ports the second half of that: any modded
 * LivingEntity fails the whitelist and the handler exits without touching
 * the write queue.
 *
 * <p>Matches the CoreProtect {@code EntityChangeBlockListener} whitelist as of
 * CP-23.2 (2025). Admins who want to audit modded mob griefing can extend the
 * set at runtime via {@code actions.entityChangeAllowlist} in config.
 *
 * @since 1.1.5
 */
public final class VanillaGrieferSet {

    /**
     * CoreProtect's hardcoded whitelist, ported. Each entry is a fully qualified
     * MC registry key {@code minecraft:<path>} that corresponds to a vanilla
     * entity class known to genuinely change block state via AI paths.
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
        "minecraft:falling_block",    // gravity conversions
        "minecraft:wind_charge",      // 1.21+ wind physics
        "minecraft:breeze_wind_charge" // 1.21+ breeze
    );

    private VanillaGrieferSet() {}

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

        if (DEFAULT_ALLOWLIST.contains(entityRegistryKey)) return true;
        if (configAllowlist != null && configAllowlist.contains(entityRegistryKey)) return true;

        return false;
    }
}
