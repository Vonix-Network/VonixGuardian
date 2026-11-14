/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.event.EventSubmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import network.vonix.guardian.mc.v1_18_2.forge.support.MixinAuditSupport;

/**
 * Honeycomb wax-on producer.
 *
 * <p>Waxing a copper block replaces one block id with another
 * ({@code minecraft:copper_block} -&gt; {@code minecraft:waxed_copper_block}).
 * Vanilla does it inside {@code HoneycombItem#useOn} with a plain
 * {@code Level#setBlock}; Forge fires no event for it, so today the change leaves
 * no audit row at all and a rollback cannot know the block was ever waxed.</p>
 *
 * <p><b>Why {@code ENTITY_CHANGE_BLOCK} and not {@code CLICK}.</b> This is a
 * block identity change with a well-defined inverse, and
 * {@code ENTITY_CHANGE_BLOCK} already carries both sides of it ({@code targetId}
 * = pre-change block, {@code targetMeta} = post-change block) with a working
 * inverse in {@code RollbackEngine}. {@code CLICK} carries neither side and is
 * refused by both {@code applyInverse} and {@code applyForward}, so a CLICK row
 * here would record the event and still be unable to undo it.</p>
 *
 * <p><b>Method binding.</b> The packaged Forge runtime exposes
 * {@code HoneycombItem#m_6225_(UseOnContext)InteractionResult}; bind that SRG
 * name with {@code remap = false} so the injector never depends on a refmap
 * entry. {@code require = 1} makes a mapping change fail at apply time instead
 * of silently logging nothing.</p>
 *
 * <p>No mutable state is declared here: the pre-interaction snapshot lives in
 * {@link MixinAuditSupport}, because a field declared in a mixin is merged into
 * the target class and cannot be reasoned about as one stash.</p>
 */
@Mixin(HoneycombItem.class)
public abstract class HoneycombItemMixin {

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
            // Only a block *identity* change is a waxing. A same-block state
            // change is not one, and logging it would be a false row.
            if (after.getBlock() == before.getBlock()) return;

            String oldId = MixinAuditSupport.blockId(before);
            String newId = MixinAuditSupport.blockId(after);
            if (oldId == null || newId == null || oldId.equals(newId)) return;

            EventSubmitter s = MixinAuditSupport.submitter();
            if (s == null) return;

            s.submitEntityChangeBlock(player.getUUID(), player.getName().getString(),
                    MixinAuditSupport.worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    oldId, newId, "#interact:wax",
                    MixinAuditSupport.stateProps(before),
                    MixinAuditSupport.stateProps(after), null);
        } catch (Throwable ignored) {
        }
    }
}
