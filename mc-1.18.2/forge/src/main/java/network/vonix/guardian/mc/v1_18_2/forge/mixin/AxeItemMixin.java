/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.event.EventSubmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinAuditSupport;

/**
 * Axe wax-off (scrape) producer.
 *
 * <p>Scraping a waxed copper block with an axe swaps it back to its unwaxed id
 * inside {@code AxeItem#useOn}, with no Forge event. Today that change is
 * invisible to the audit log and unrecoverable by rollback.</p>
 *
 * <p><b>Deliberately narrow.</b> {@code AxeItem#useOn} also handles log
 * stripping and copper oxidation scraping. Those are different actions, and this
 * lane owns honeycomb wax/scrape only. The filter is exact rather than
 * heuristic: a row is emitted only when the pre-state block is a key of
 * {@link HoneycombItem#WAX_OFF_BY_BLOCK} <em>and</em> the post-state is that
 * key's mapped value — vanilla's own definition of "this axe click removed wax".
 * Stripping and oxidation scraping never satisfy it, so they never produce a row
 * from this mixin. Log stripping in particular is a wood transform, not a wax
 * action; logging it here would file one action as another.</p>
 *
 * <p>Row shape matches {@link HoneycombItemMixin}: {@code ENTITY_CHANGE_BLOCK}
 * carrying both sides of the transition, so the existing inverse in
 * {@code RollbackEngine} restores the waxed block.</p>
 *
 * <p>No mutable state is declared here: the pre-interaction snapshot lives in
 * {@link MixinAuditSupport}, because a field declared in a mixin is merged into
 * the target class and cannot be reasoned about as one stash.</p>
 */
@Mixin(AxeItem.class)
public abstract class AxeItemMixin {

    @Inject(
            method = "m_6225_(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeUseOn(UseOnContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (ctx == null) return;
            Level level = ctx.getLevel();
            if (level == null || level.isClientSide) return;
            MixinAuditSupport.pushUseBefore(level, ctx.getClickedPos());
        } catch (Throwable ignored) {
        }
    }

    @Inject(
            method = "m_6225_(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterUseOn(UseOnContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (ctx == null) return;
            BlockState before = MixinAuditSupport.popUseBefore();
            if (before == null) return;
            Level level = ctx.getLevel();
            Player player = ctx.getPlayer();
            if (level == null || level.isClientSide || player == null) return;
            BlockPos pos = ctx.getClickedPos();
            if (pos == null) return;
            BlockState after = level.getBlockState(pos);
            if (after == null || after == before) return;

            Block unwaxed = vg$waxOffTarget(before.getBlock());
            if (unwaxed == null || after.getBlock() != unwaxed) return;

            String oldId = MixinAuditSupport.blockId(before);
            String newId = MixinAuditSupport.blockId(after);
            if (oldId == null || newId == null || oldId.equals(newId)) return;

            EventSubmitter s = MixinAuditSupport.submitter();
            if (s == null) return;

            s.submitEntityChangeBlock(player.getUUID(), player.getName().getString(),
                    MixinAuditSupport.worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    oldId, newId, "#interact:wax_off",
                    MixinAuditSupport.stateProps(before),
                    MixinAuditSupport.stateProps(after), null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * @param waxedBlock candidate pre-scrape block
     * @return the unwaxed block vanilla maps {@code waxedBlock} to, or
     *         {@code null} when the block is not a waxed block at all
     */
    private static Block vg$waxOffTarget(Block waxedBlock) {
        try {
            if (waxedBlock == null) return null;
            Map<Block, Block> waxOffByBlock = HoneycombItem.WAX_OFF_BY_BLOCK.get();
            return waxOffByBlock == null ? null : waxOffByBlock.get(waxedBlock);
        } catch (Throwable t) {
            return null;
        }
    }
}
