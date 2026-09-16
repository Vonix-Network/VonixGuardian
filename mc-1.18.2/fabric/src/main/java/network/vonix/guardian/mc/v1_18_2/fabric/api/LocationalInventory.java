/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.fabric.api;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;

/** Marker interface exposing the backing block position of container inventories. */
public interface LocationalInventory {
    @NotNull
    BlockPos vg$getLocation();
}
