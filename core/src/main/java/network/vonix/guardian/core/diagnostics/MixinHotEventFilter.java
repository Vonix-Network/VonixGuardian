package network.vonix.guardian.core.diagnostics;

/**
 * Utility that classifies whether an incoming {@code sourceTag} was produced by
 * one of the v1.3.0 hot-tick mixin pipelines (W1a fire, W1b natural growth /
 * spread, W1c dispenser / decay / ice / concrete-powder).
 *
 * <p>Contract with the W1 mixin waves: every mixin-authored submission carries
 * a {@code sourceTag} beginning with one of the reserved prefixes:</p>
 * <ul>
 *   <li>{@code "#fire"}       — {@code FireBlockMixin} (W1a): burn / ignite</li>
 *   <li>{@code "#natural"}    — natural block spread/form/fade/decay (W1b/c):
 *                               {@code SpreadingSnowyDirtBlockMixin},
 *                               {@code LeavesBlockMixin},
 *                               {@code IceBlockMixin},
 *                               {@code ConcretePowderBlockMixin}</li>
 *   <li>{@code "#dispenser"}  — {@code DispenserBlockMixin} (W1c)</li>
 *   <li>{@code "#fluid"}      — {@code LiquidBlockMixin} (v1.3.1 X3):
 *                               water/lava spread</li>
 * </ul>
 *
 * <p>When {@code actions.mixinHotEvents=false}, {@code Guardian.submit(Action)}
 * short-circuits any action tagged with one of these prefixes before the gate
 * or queue see it. This is the operator kill-switch for load-shedding.</p>
 *
 * <p>Static/stateless; safe from any thread. Zero-allocation on the hot path
 * (uses {@link String#startsWith(String)} against interned constants).</p>
 *
 * @since 1.3.0 (W4)
 */
public final class MixinHotEventFilter {

    /** Reserved sourceTag prefix for W1a {@code FireBlockMixin} events. */
    public static final String PREFIX_FIRE      = "#fire";

    /** Reserved sourceTag prefix for W1b natural-growth / spread / form / fade / decay mixin events. */
    public static final String PREFIX_NATURAL   = "#natural";

    /** Reserved sourceTag prefix for W1c {@code DispenserBlockMixin} events. */
    public static final String PREFIX_DISPENSER = "#dispenser";

    /**
     * Reserved sourceTag prefix for v1.3.1 X3 {@code LiquidBlockMixin} events
     * (water/lava natural spread).
     */
    public static final String PREFIX_FLUID     = "#fluid";

    private MixinHotEventFilter() {}

    /**
     * @param sourceTag the raw {@code Action.sourceTag()} (may be {@code null})
     * @return {@code true} iff {@code sourceTag} is a W1a/b/c mixin-authored tag
     */
    public static boolean isMixinSourced(String sourceTag) {
        if (sourceTag == null || sourceTag.isEmpty() || sourceTag.charAt(0) != '#') {
            return false;
        }
        return sourceTag.startsWith(PREFIX_FIRE)
            || sourceTag.startsWith(PREFIX_NATURAL)
            || sourceTag.startsWith(PREFIX_DISPENSER)
            || sourceTag.startsWith(PREFIX_FLUID);
    }
}
