/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
 * Records cauldron fill / empty / layer changes as one {@link ActionType#CLICK}
 * row per interaction that actually moved the level.
 *
 * <h2>Why one injection covers every variant</h2>
 * Empty, water, powder-snow and lava cauldrons all dispatch their item
 * interactions through {@link AbstractCauldronBlock#use}, and none of the four
 * subclasses overrides it. So buckets, bottles, wet/dry sponge, dyed leather and
 * dyed banners are all captured here without a per-variant mixin.
 *
 * <h2>Why no pre-state field</h2>
 * A {@code Block} is a singleton shared by every cauldron in the world, so a
 * "state seen at HEAD" field on the mixin would be shared across positions. It
 * is not needed: the {@code state} argument <em>is</em> the pre-interaction
 * state, so the post-state can be read from the level and compared against it
 * without storing anything.
 *
 * <p>The row is emitted only when the state actually changed, which keeps a
 * refused right-click (wrong item, full cauldron, empty hand) out of the log.
 * Both states are rendered into {@code targetMeta} so an operator sees
 * {@code level=3 -> level=2} rather than just "clicked a cauldron".
 *
 * <p>Binding: packaged Forge 1.18.2 runs the SRG-mapped
 * {@code m_6227_(BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)}.
 */
@Mixin(AbstractCauldronBlock.class)
public abstract class CauldronUseMixin {

    /** {@code #interact:cauldron} — player-initiated, so outside the hot-event kill-switch. */
    @Unique
    private static final String vg$cauldronSource = "#interact:cauldron";

    @Inject(
            method = "m_6227_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterUse(BlockState state, Level level, BlockPos pos, Player player,
                             InteractionHand hand, BlockHitResult hit,
                             CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (state == null || level == null || pos == null || player == null) return;
            if (level.isClientSide) return;
            BlockPos at = pos.immutable();
            BlockState after = level.getBlockState(at);
            if (after == null || after.equals(state)) return;
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            g.submit(new ActionBuilder()
                    .type(ActionType.CLICK)
                    .actorUuid(player.getUUID())
                    .actorName(player.getName().getString())
                    .worldId(WorldKey.of(level))
                    .position(at.getX(), at.getY(), at.getZ())
                    .targetId(blockId(after))
                    .targetMeta("was=" + describe(state) + ";now=" + describe(after))
                    .sourceTag(vg$cauldronSource)
                    .build());
        } catch (Throwable ignored) {
            // Mixins must never escape into the server thread.
        }
    }

    /** {@code minecraft:water_cauldron[level=2]} — id plus the fill level when the variant has one. */
    @Unique
    private static String describe(BlockState state) {
        if (state == null) return "?";
        String id = blockId(state);
        try {
            if (state.hasProperty(LayeredCauldronBlock.LEVEL)) {
                return id + "[level=" + state.getValue(LayeredCauldronBlock.LEVEL) + "]";
            }
        } catch (Throwable ignored) {
            // property absent on this variant
        }
        return id;
    }

    @Unique
    private static String blockId(BlockState state) {
        try {
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }
}
