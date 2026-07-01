/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.common;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.util.Locale;

/**
 * W3-B15 wire: pull the CoreProtect-v24-parity sign metadata (side, dye color,
 * waxed flag) off a {@link SignBlockEntity} on MC 1.20+.
 *
 * <p>Front vs back is a caller-side decision (the caller knows which side the
 * editing player interacted with); this helper reads the dye color and waxed
 * flag for the requested side.
 *
 * <p>All extraction is defensive — any {@link Throwable} short-circuits to
 * {@code null} for the affected field so a sign-metadata read failure never
 * poisons the underlying event. The joined-lines string is NOT this helper's
 * concern; the loader continues to build that itself.
 */
public final class SignMetadataExtractor {

    /** Side literal used in the {@code sign_side} column for front-side rows. */
    public static final String SIDE_FRONT = "front";
    /** Side literal used in the {@code sign_side} column for back-side rows. */
    public static final String SIDE_BACK = "back";

    private SignMetadataExtractor() {}

    /**
     * Read metadata for the front side of the given {@link BlockEntity} — a
     * no-op returning three-null result if it's not a {@code SignBlockEntity}
     * or the level threw during read.
     */
    public static Result front(BlockEntity be) {
        return read(be, /* back = */ false);
    }

    /** Same as {@link #front} but for the back side (1.20+ two-sided signs). */
    public static Result back(BlockEntity be) {
        return read(be, /* back = */ true);
    }

    private static Result read(BlockEntity be, boolean back) {
        String side = back ? SIDE_BACK : SIDE_FRONT;
        String dye = null;
        Boolean waxed = null;
        try {
            if (be instanceof SignBlockEntity sign) {
                SignText text = back ? sign.getBackText() : sign.getFrontText();
                if (text != null) {
                    DyeColor c = text.getColor();
                    if (c != null) {
                        dye = c.getName().toLowerCase(Locale.ROOT);
                    }
                }
                waxed = sign.isWaxed();
            }
        } catch (Throwable t) {
            // Defensive: mojang refactors the sign API more or less every version.
            // We prefer null over crashing the event dispatch.
        }
        return new Result(side, dye, waxed);
    }

    /**
     * Extracted metadata triple. All three fields are nullable; a non-sign
     * block-entity produces {@code (side, null, null)}.
     */
    public record Result(String side, String dyeColor, Boolean waxed) {}
}
