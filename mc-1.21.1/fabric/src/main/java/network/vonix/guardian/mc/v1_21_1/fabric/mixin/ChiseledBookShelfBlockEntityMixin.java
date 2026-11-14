/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_21_1.fabric.api.LocationalInventory;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marker mixin for the chiseled bookshelf, which is a container that is
 * <em>not</em> a {@link net.minecraft.world.level.block.entity.BaseContainerBlockEntity}.
 *
 * <p><b>Why this exists.</b> {@code BaseContainerBlockEntityMixin} covers every
 * chest, barrel, shulker box, hopper, furnace and brewing stand because they all
 * extend {@code BaseContainerBlockEntity}. A chiseled bookshelf extends
 * {@code BlockEntity} directly and implements {@code Container} itself, so it
 * fell outside that hierarchy and its per-slot insertion and removal produced
 * <em>no audit row at all</em> — the exact failure mode an audit tool exists to
 * prevent. Six slots holding books, reachable by any player, was a blind spot.</p>
 *
 * <p><b>Coverage.</b> Shelf books are real items: a book removed by a player is
 * indistinguishable, from an audit standpoint, from an item withdrawn from a
 * chest. Once the shelf reports a location through {@link LocationalInventory},
 * the shared slot path records it with the same attribution.</p>
 */
@Mixin(ChiseledBookShelfBlockEntity.class)
public abstract class ChiseledBookShelfBlockEntityMixin extends BlockEntity implements LocationalInventory {

    public ChiseledBookShelfBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @NotNull
    @Override
    public BlockPos vg$getLocation() {
        return this.worldPosition;
    }
}
