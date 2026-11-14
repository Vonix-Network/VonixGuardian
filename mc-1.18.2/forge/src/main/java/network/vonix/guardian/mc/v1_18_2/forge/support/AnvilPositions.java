/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;

/**
 * Resolves the block position behind a menu's {@link ContainerLevelAccess}.
 *
 * <p>This is a plain class, not a mixin, on purpose: the lookup needs a
 * {@code BiFunction} lambda, and a lambda written inside a mixin handler is
 * compiled to a synthetic method <em>of the mixin class</em>. Mixin copies the
 * handler body into the target class, which leaves that synthetic lambda body
 * behind in the (never-loaded) mixin class, so the call site ends up invoking
 * through a class that does not exist at runtime. Keeping the lambda in an
 * ordinary class avoids the whole problem.
 */
final class AnvilPositions {

    private AnvilPositions() {
    }

    /**
     * @param access a menu's level access; may be {@code null} or the
     *               {@link ContainerLevelAccess#NULL} sentinel
     * @return the immutable anvil position, or {@code null} when the access
     *         carries no server-side position
     */
    static BlockPos resolve(ContainerLevelAccess access) {
        if (access == null) {
            return null;
        }
        try {
            return access.evaluate(AnvilPositions::pick, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Server-side position only; a client level would report a meaningless coordinate. */
    private static BlockPos pick(Level level, BlockPos pos) {
        if (level == null || pos == null || level.isClientSide) {
            return null;
        }
        return pos.immutable();
    }
}
