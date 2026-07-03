/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;

/**
 * Marker interface applied to block-entity inventories by
 * {@link BaseContainerBlockEntityMixin}. Any code path holding a
 * {@link net.minecraft.world.Container} can cast to {@code LocationalInventory}
 * to recover the on-world position of the backing block-entity.
 *
 * <p>Mirrors the Ledger (Quilt Server Tools) design — see Ledger's
 * {@code com.github.quiltservertools.ledger.actionutils.LocationalInventory}
 * plus {@code BaseContainerBlockEntityMixin}. VG v1.3.1 X9 adds this marker so
 * downstream slot-level tracking (future waves) can attribute changes to a
 * physical block position without threading a {@code BlockPos} through every
 * caller.</p>
 */
public interface LocationalInventory {
    @NotNull
    BlockPos vg$getLocation();
}
