/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marker mixin that makes every {@link BaseContainerBlockEntity} implement
 * {@link LocationalInventory} — the on-world position of the container is now
 * recoverable from any {@link net.minecraft.world.Container} reference that is
 * really a {@link BaseContainerBlockEntity}.
 *
 * <p><b>Ledger parity.</b> Mirrors {@code
 * com.github.quiltservertools.ledger.mixin.BaseContainerBlockEntityMixin}
 * (Ledger master branch, {@code src/main/java/.../BaseContainerBlockEntityMixin.java}
 * lines 12–22). Ledger's {@code SlotMixin} / {@code ContainersMixin} use the
 * same interface to attribute item-remove events to a real position instead of
 * threading the position through every caller.</p>
 *
 * <p><b>Coverage.</b> This applies to chests + trapped chests + ender chests +
 * barrels + shulker boxes + hoppers + furnaces + smokers + blast furnaces +
 * brewing stands + dispensers + droppers — everything that extends
 * {@link BaseContainerBlockEntity}. Compound (double) chests are handled at the
 * slot-mixin path via {@code CompoundContainer} which routes each slot to the
 * underlying single-chest {@code BaseContainerBlockEntity}.</p>
 *
 * <p><b>Constructor.</b> {@code BaseContainerBlockEntity}'s only constructor
 * takes {@code (BlockEntityType, BlockPos, BlockState)}; we must mirror the
 * same shape for the mixin's synthetic superclass call to compile against
 * {@link BlockEntity}.</p>
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin extends BlockEntity implements LocationalInventory {

    public BaseContainerBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @NotNull
    @Override
    public BlockPos vg$getLocation() {
        return this.worldPosition;
    }
}
