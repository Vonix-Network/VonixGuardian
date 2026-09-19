package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import net.minecraft.world.level.levelgen.structure.JunglePyramidPiece;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(JunglePyramidPiece.class)
public class MixinJungleTempleGenerator {

    private final AtomicBoolean placedMainChestAtomic = new AtomicBoolean();
    private final AtomicBoolean placedHiddenChestAtomic = new AtomicBoolean();
    private final AtomicBoolean placedTrap1Atomic = new AtomicBoolean();
    private final AtomicBoolean placedTrap2Atomic = new AtomicBoolean();

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/JunglePyramidPiece;placedMainChest:Z", opcode = Opcodes.PUTFIELD))
    private void redirectSetPlacedMainChest(JunglePyramidPiece jungleTempleGenerator, boolean value) {
        this.placedMainChestAtomic.compareAndSet(false, value);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/JunglePyramidPiece;placedMainChest:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetPlacedMainChest(JunglePyramidPiece jungleTempleGenerator) {
        return this.placedMainChestAtomic.get();
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/JunglePyramidPiece;placedHiddenChest:Z", opcode = Opcodes.PUTFIELD))
    private void redirectSetHiddenChest(JunglePyramidPiece jungleTempleGenerator, boolean value) {
        this.placedHiddenChestAtomic.compareAndSet(false, value);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/JunglePyramidPiece;placedHiddenChest:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetHiddenChest(JunglePyramidPiece jungleTempleGenerator) {
        return this.placedHiddenChestAtomic.get();
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/JunglePyramidPiece;placedTrap1:Z", opcode = Opcodes.PUTFIELD))
    private void redirectSetPlacedTrap1(JunglePyramidPiece jungleTempleGenerator, boolean value) {
        this.placedTrap1Atomic.compareAndSet(false, value);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/JunglePyramidPiece;placedTrap1:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetPlacedTrap1(JunglePyramidPiece jungleTempleGenerator) {
        return this.placedTrap1Atomic.get();
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/JunglePyramidPiece;placedTrap2:Z", opcode = Opcodes.PUTFIELD))
    private void redirectSetPlacedTrap2(JunglePyramidPiece jungleTempleGenerator, boolean value) {
        this.placedTrap2Atomic.compareAndSet(false, value);
    }

    @Dynamic
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/JunglePyramidPiece;placedTrap2:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetPlacedTrap2(JunglePyramidPiece jungleTempleGenerator) {
        return this.placedTrap2Atomic.get();
    }

}
