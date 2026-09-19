package network.vonix.threadedhorizons.mixin.access;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BlockEntity.class)
public interface IBlockEntity {

    @Invoker("saveAdditional")
    void invokeWriteNbt(CompoundTag nbt);

}
