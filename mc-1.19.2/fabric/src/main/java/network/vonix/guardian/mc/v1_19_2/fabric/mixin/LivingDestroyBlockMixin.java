/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.mc.v1_19_2.fabric.FabricMixinBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LIVING_DESTROY_BLOCK — mob-caused block removal (ravager, ender-dragon,
 * modded griefers). Mixes {@code Mob#getPathfindingMalus}? No — the accurate
 * hook is {@code LivingEntity#canAttackType} — also wrong. The right method is
 * {@code Mob#doHurtTarget} for combat, not blocks. For block destruction we use
 * the standard entry: {@code Level#destroyBlock} is called from various mob AI
 * goals with the entity as the "breaker" argument.
 *
 * <p>Since {@code Level#destroyBlock} signature is
 * {@code destroyBlock(BlockPos, boolean, Entity, int)}, we inject at HEAD and
 * dispatch only when the breaker is a non-player {@link LivingEntity}.
 */
@Mixin(Level.class)
public abstract class LivingDestroyBlockMixin {

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"),
            require = 0)
    private void vg$onDestroyBlock(BlockPos pos, boolean drop,
                                   net.minecraft.world.entity.Entity breaker, int recursionLeft,
                                   CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!(breaker instanceof LivingEntity le)) return;
            if (breaker instanceof net.minecraft.world.entity.player.Player) return;
            if (!(breaker instanceof Mob)) return;
            Level self = (Level) (Object) this;
            BlockState old = self.getBlockState(pos);
            FabricMixinBridge.livingDestroyBlock(le, self, pos, old);
        } catch (Throwable ignored) {}
    }
}
