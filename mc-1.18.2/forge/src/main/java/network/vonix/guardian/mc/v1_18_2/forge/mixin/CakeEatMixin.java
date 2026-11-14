/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.mc.v1_18_2.common.WorldKey;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records one eaten cake slice as an {@link ActionType#CLICK} row.
 *
 * <p>Targets the protected static {@code CakeBlock.eat} helper rather than
 * {@code CakeBlock.use} because the candle-cake block calls {@code eat}
 * directly, so a single injection covers both plain cake and cake-with-candle.
 *
 * <p>A row is emitted only when vanilla reports
 * {@link InteractionResult#SUCCESS} — {@code eat} returns {@code PASS} for a
 * player who cannot eat (no hunger, creative spectator), and logging that would
 * claim a slice was consumed when nothing changed.
 *
 * <p>Binding: packaged Forge 1.18.2 runs the SRG-mapped
 * {@code m_51185_(LevelAccessor, BlockPos, BlockState, Player)}.
 */
@Mixin(CakeBlock.class)
public abstract class CakeEatMixin {

    /** {@code #interact:cake} — player-initiated, so outside the hot-event kill-switch. */
    @Unique
    private static final String vg$cakeSource = "#interact:cake";

    @Inject(
            method = "m_51185_(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private static void vg$afterEat(LevelAccessor accessor, BlockPos pos, BlockState state, Player player,
                                    CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (cir == null || cir.getReturnValue() != InteractionResult.SUCCESS) return;
            if (player == null || pos == null || state == null) return;
            if (!(accessor instanceof Level level) || level.isClientSide) return;
            int bites = 0;
            try {
                bites = state.getValue(CakeBlock.BITES);
            } catch (Throwable ignored) {
                // property absent — keep the row, drop the counter
            }
            BlockPos at = pos.immutable();
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            g.submit(new ActionBuilder()
                    .type(ActionType.CLICK)
                    .actorUuid(player.getUUID())
                    .actorName(player.getName().getString())
                    .worldId(WorldKey.of(level))
                    .position(at.getX(), at.getY(), at.getZ())
                    .targetId(blockId(state))
                    .targetMeta("bites=" + bites + ";" + afterEat(level, at))
                    .sourceTag(vg$cakeSource)
                    .build());
        } catch (Throwable ignored) {
            // Mixins must never escape into the server thread.
        }
    }

    /** Post-eat state of the cake cell: {@code bites=N} or {@code removed}. */
    @Unique
    private static String afterEat(Level level, BlockPos at) {
        try {
            BlockState after = level.getBlockState(at);
            if (after == null || after.isAir()) return "removed";
            if (after.getBlock() instanceof CakeBlock && after.hasProperty(CakeBlock.BITES)) {
                return "bites=" + after.getValue(CakeBlock.BITES);
            }
            return "now=" + blockId(after);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Unique
    private static String blockId(BlockState state) {
        try {
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:cake";
        } catch (Throwable t) {
            return "minecraft:cake";
        }
    }
}
