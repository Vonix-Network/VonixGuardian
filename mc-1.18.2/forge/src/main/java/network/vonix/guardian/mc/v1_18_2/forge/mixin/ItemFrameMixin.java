/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.mc.v1_18_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_18_2.forge.ForgeMixinBridge;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LANE 8 — item frame content and rotation auditing.
 *
 * <h2>Why this mixin exists</h2>
 * Item frames are entities, not blocks. Forge fires
 * {@code PlayerInteractEvent.EntityInteractSpecific} for the
 * {@code interactAt} path and {@code PlayerInteractEvent.EntityInteract} for the
 * plain {@code interact} path, but the 1.18.2 Forge cell subscribes to neither.
 * A frame could therefore be filled, emptied or spun with <em>no audit row at
 * all</em>.
 *
 * <h2>Which vanilla method</h2>
 * {@code ItemFrame.interact} is the single player-driven mutation point for a
 * frame: an empty frame accepts the held stack ({@code setItem}), and an
 * occupied frame rotates one step ({@code setRotation}). Rotation through
 * {@code interact} is confirmed reachable on the server: the client sends an
 * {@code interactAt} packet first, {@code Entity.interactAt} defaults to
 * {@code PASS} for a frame (which does not override it), and the client then
 * falls back to a plain interaction packet that dispatches to
 * {@code ItemFrame.interact}.
 *
 * <p>{@code setItem}/{@code setRotation} are deliberately <em>not</em> hooked
 * directly: both are also called from {@code readAdditionalSaveData}, so a
 * chunk reload would replay one row per frame in the chunk.</p>
 *
 * <h2>Action type</h2>
 * The row is {@link network.vonix.guardian.core.action.ActionType#CLICK} — the
 * interaction family. A frame's contents are not a block-entity container, so
 * {@code CONTAINER_*}/{@code HANGING_*} types would advertise a rollback the
 * mutator cannot perform (it resolves containers through
 * {@code Level#getBlockEntity}). CLICK is audit-only and refuses at dispatch
 * with a named reason, which keeps the "a rollback must not fail" invariant
 * intact.
 */
@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {

    /** Frame contents observed before the interaction. */
    @Unique
    private ItemStack vg$frameItemBefore;

    /** Rotation observed before the interaction. */
    @Unique
    private int vg$frameRotationBefore;

    /** Set only when the pre-state was captured successfully for this call. */
    @Unique
    private boolean vg$frameArmed;

    /**
     * Capture the frame's pre-state.
     *
     * <p>{@code interact} has several early returns (spectator, fixed frame,
     * no-op hands), so this only arms the RETURN handler — it never emits.
     * On any failure the guard stays disarmed and the RETURN handler is a
     * no-op rather than a half-populated row.</p>
     */
    @Inject(
            method = "m_6096_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeFrameInteract(Player player, InteractionHand hand,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        vg$frameArmed = false;
        try {
            ItemFrame self = (ItemFrame) (Object) this;
            Level level = self.level;
            if (player == null || level == null || level.isClientSide) {
                return;
            }
            ItemStack current = self.getItem();
            vg$frameItemBefore = current == null ? ItemStack.EMPTY : current.copy();
            vg$frameRotationBefore = self.getRotation();
            vg$frameArmed = true;
        } catch (Throwable ignored) {
            vg$frameArmed = false;
        }
    }

    /**
     * Emit one row per distinct mutation that actually occurred.
     *
     * <p>A single right-click can both insert an item <em>and</em> change
     * rotation in modded frames, so the two are tested independently. In
     * practice vanilla performs at most one of them per call.</p>
     */
    @Inject(
            method = "m_6096_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterFrameInteract(Player player, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (!vg$frameArmed) {
            return;
        }
        vg$frameArmed = false;
        try {
            ItemFrame self = (ItemFrame) (Object) this;
            Level level = self.level;
            if (player == null || level == null) {
                return;
            }
            ItemStack before = vg$frameItemBefore == null ? ItemStack.EMPTY : vg$frameItemBefore;
            ItemStack after = self.getItem();
            int rotationBefore = vg$frameRotationBefore;
            int rotationAfter = self.getRotation();
            vg$frameItemBefore = null;

            boolean contentChanged = !vg$sameContents(before, after);
            boolean rotated = rotationAfter != rotationBefore;
            if (!contentChanged && !rotated) {
                return;
            }

            var pos = self.blockPosition();
            String worldId = level.dimension().location().toString();
            String frameType = EntitySentinel.of(self);

            if (contentChanged) {
                boolean emptied = after == null || after.isEmpty();
                ItemStack subject = emptied ? before : after;
                ItemStack opposite = emptied ? after : before;
                vg$emit(player, worldId, pos.getX(), pos.getY(), pos.getZ(),
                        ForgeMixinBridge.itemId(subject),
                        vg$frameMeta(frameType, ForgeMixinBridge.itemId(opposite),
                                ForgeMixinBridge.itemId(subject), rotationBefore, rotationAfter));
            }
            if (rotated) {
                ItemStack shown = after == null || after.isEmpty() ? before : after;
                vg$emit(player, worldId, pos.getX(), pos.getY(), pos.getZ(),
                        frameType,
                        vg$frameMeta(frameType, ForgeMixinBridge.itemId(shown),
                                ForgeMixinBridge.itemId(shown), rotationBefore, rotationAfter));
            }
        } catch (Throwable ignored) {
            // A mixin must never throw into the server thread.
        }
    }

    /** True when both stacks hold the same item, tags and count (or both empty). */
    @Unique
    private static boolean vg$sameContents(ItemStack a, ItemStack b) {
        boolean aEmpty = a == null || a.isEmpty();
        boolean bEmpty = b == null || b.isEmpty();
        if (aEmpty || bEmpty) {
            return aEmpty == bEmpty;
        }
        return ItemStack.isSameItemSameTags(a, b) && a.getCount() == b.getCount();
    }

    /** Compact JSON detail persisted to {@code target_meta}. */
    @Unique
    private static String vg$frameMeta(String frameType, String fromItem, String toItem,
                                       int rotationFrom, int rotationTo) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"frame\":\"").append(frameType)
          .append("\",\"from\":\"").append(fromItem)
          .append("\",\"to\":\"").append(toItem)
          .append("\",\"rotation\":").append(rotationFrom)
          .append("->").append(rotationTo).append('}');
        return sb.toString();
    }

    /** Submit an audit-only interaction row. Never throws. */
    @Unique
    private static void vg$emit(Player player, String worldId, int x, int y, int z,
                                String targetId, String meta) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) {
                return;
            }
            g.submitAccepted(new network.vonix.guardian.core.action.ActionBuilder()
                    .type(network.vonix.guardian.core.action.ActionType.CLICK)
                    .actorUuid(player.getUUID())
                    .actorName(player.getName().getString())
                    .worldId(worldId)
                    .position(x, y, z)
                    .targetId(targetId)
                    .targetMeta(meta)
                    .amount(1)
                    .sourceTag("#interact:item_frame")
                    .build());
        } catch (Throwable ignored) {
            // defensive: never escape into the server thread
        }
    }
}
