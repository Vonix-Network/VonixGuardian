/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.Locale;

/**
 * W3-B15 wire: pull the CoreProtect-v24-parity sign metadata off a
 * {@link SignBlockEntity} on MC 1.19.2.
 *
 * <h2>Version limits</h2>
 * MC 1.19.2 has:
 * <ul>
 *   <li>NO back side — signs are single-sided; we always return
 *       {@link #SIDE_FRONT}.</li>
 *   <li>NO {@code isWaxed} flag — waxed signs shipped in 1.20; we always
 *       return {@code null} for waxed.</li>
 *   <li>YES dye color — {@code SignBlockEntity.getColor()} exists and returns
 *       a {@link DyeColor}.</li>
 * </ul>
 *
 * <p>Extraction is fully defensive; anything odd short-circuits to {@code null}
 * for the affected field.
 */
public final class SignMetadataExtractor {

    /** Side literal used in the {@code sign_side} column for front-side rows. */
    public static final String SIDE_FRONT = "front";

    private SignMetadataExtractor() {}

    /**
     * Read sign metadata for the given {@link BlockEntity}. On 1.19.2 there is
     * no back side, so the returned {@code side} is always {@link #SIDE_FRONT}
     * and {@code waxed} is always {@code null}.
     */
    public static Result front(BlockEntity be) {
        String dye = null;
        try {
            if (be instanceof SignBlockEntity sign) {
                DyeColor c = sign.getColor();
                if (c != null) {
                    dye = c.getName().toLowerCase(Locale.ROOT);
                }
            }
        } catch (Throwable t) {
            // Defensive — see SignMetadataExtractor class javadoc.
        }
        return new Result(SIDE_FRONT, dye, null);
    }

    /**
     * Extracted metadata triple; {@code side} is always {@link #SIDE_FRONT} on
     * 1.19.2 and {@code waxed} is always {@code null}.
     */
    public record Result(String side, String dyeColor, Boolean waxed) {}
}
