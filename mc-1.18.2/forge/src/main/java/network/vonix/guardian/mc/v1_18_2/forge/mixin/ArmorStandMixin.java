/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.action.NbtPayload;
import network.vonix.guardian.mc.v1_18_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_18_2.common.SourceTagger;
import network.vonix.guardian.mc.v1_18_2.forge.ForgeMixinBridge;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LANE 8 — armor stand equipment swaps and destruction.
 *
 * <h2>Why this mixin exists</h2>
 * Two separate gaps, both silent today:
 * <ol>
 *   <li><b>Equipment.</b> Right-clicking a stand equips, swaps or removes an
 *       item through {@code ArmorStand.interactAt}. The 1.18.2 Forge cell
 *       subscribes to {@code PlayerInteractEvent.EntityInteract} but not to
 *       {@code EntityInteractSpecific}, which is the event the location-bearing
 *       {@code interactAt} path fires. No row was produced.</li>
 *   <li><b>Destruction.</b> {@code ArmorStand} never calls {@code LivingEntity.die()}
 *       — {@code brokenByPlayer} / {@code brokenByAnything} both end in
 *       {@code kill()}, which removes the entity directly. {@code LivingDeathEvent}
 *       therefore never fires for a stand, so {@code ForgeEvents.onLivingDeath}
 *       cannot see a broken stand and the kit on it vanishes from the audit.</li>
 * </ol>
 *
 * <h2>Hook choice</h2>
 * <ul>
 *   <li>Equipment is read around {@code interactAt}, not around {@code setItemSlot}:
 *       {@code setItemSlot} is also reached from {@code readAdditionalSaveData},
 *       so hooking it would replay one row per stand on every chunk load.</li>
 *   <li>Destruction is hooked at {@code kill()} HEAD, the single funnel for both
 *       break paths and for {@code /kill}. Chunk unload uses {@code discard()},
 *       a different method, so it cannot produce a false kill row.</li>
 * </ul>
 *
 * <h2>Action types</h2>
 * Equipment changes are {@link ActionType#CLICK} (interaction, audit-only): a
 * stand is an entity, so no container mutator can reverse it, and claiming
 * {@code CONTAINER_*} would queue a rollback that fails at dispatch. Destruction
 * is {@link ActionType#ENTITY_KILL}, which <em>is</em> reversible through
 * {@code tryRespawnEntity}; the entity NBT captured at kill time is what makes
 * that restore faithful.
 */
@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin {

    /** Per-slot contents observed before {@code interactAt}. */
    @Unique
    private ItemStack[] vg$standBefore;

    /** Slot names parallel to {@link #vg$standBefore}. */
    @Unique
    private String[] vg$standSlots;

    /** Armed only when the pre-state was captured for this call. */
    @Unique
    private boolean vg$standArmed;

    /**
     * Damage source seen in {@code hurt}, used to attribute the later
     * {@code kill()}. {@code brokenByPlayer}/{@code brokenByAnything} are private
     * and carry no injection point that also reveals the actor.
     */
    @Unique
    private DamageSource vg$standLastDamage;

    /**
     * When {@link #vg$standLastDamage} was recorded, in wall-clock millis.
     *
     * <p>A break runs {@code hurt -> causeDamage/brokenBy* -> kill} inside a
     * single call stack, so the source is only honoured inside a short window
     * (see {@code vg$onStandKill}). Without the window, a player who merely hits
     * a stand and walks away would still be blamed for a later {@code /kill} of
     * that same stand.</p>
     */
    @Unique
    private long vg$standLastDamageAt;

    // ------------------------------------------------------------------ equipment

    /**
     * Snapshot every equipment slot before the interaction.
     *
     * <p>{@code interactAt} also returns early (disabled slots, spectator, marker
     * stands), so this only arms the RETURN handler and never emits.</p>
     */
    @Inject(
            method = "m_7111_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeStandInteract(Player player, Vec3 hitVec, InteractionHand hand,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        vg$standArmed = false;
        try {
            ArmorStand self = (ArmorStand) (Object) this;
            Level level = self.level;
            if (player == null || level == null || level.isClientSide) {
                return;
            }
            EquipmentSlot[] all = EquipmentSlot.values();
            ItemStack[] before = new ItemStack[all.length];
            String[] names = new String[all.length];
            for (int i = 0; i < all.length; i++) {
                ItemStack stack = self.getItemBySlot(all[i]);
                before[i] = stack == null ? ItemStack.EMPTY : stack.copy();
                names[i] = all[i].getName();
            }
            vg$standBefore = before;
            vg$standSlots = names;
            vg$standArmed = true;
        } catch (Throwable ignored) {
            vg$standArmed = false;
        }
    }

    /** Emit one row per equipment slot whose contents actually changed. */
    @Inject(
            method = "m_7111_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$afterStandInteract(Player player, Vec3 hitVec, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (!vg$standArmed) {
            return;
        }
        vg$standArmed = false;
        ItemStack[] before = vg$standBefore;
        String[] names = vg$standSlots;
        vg$standBefore = null;
        vg$standSlots = null;
        if (before == null || names == null) {
            return;
        }
        try {
            ArmorStand self = (ArmorStand) (Object) this;
            Level level = self.level;
            if (player == null || level == null) {
                return;
            }
            EquipmentSlot[] all = EquipmentSlot.values();
            if (all.length != before.length) {
                return;
            }
            var pos = self.blockPosition();
            String worldId = level.dimension().location().toString();
            String standType = EntitySentinel.of(self);

            List<ItemStack[]> pairs = new ArrayList<>(1);
            List<String> slotNames = new ArrayList<>(1);
            for (int i = 0; i < all.length; i++) {
                ItemStack now = self.getItemBySlot(all[i]);
                ItemStack prev = before[i] == null ? ItemStack.EMPTY : before[i];
                if (vg$same(prev, now)) {
                    continue;
                }
                pairs.add(new ItemStack[]{prev, now == null ? ItemStack.EMPTY : now});
                slotNames.add(names[i] == null ? all[i].getName() : names[i]);
            }
            for (int j = 0; j < pairs.size(); j++) {
                ItemStack prev = pairs.get(j)[0];
                ItemStack now = pairs.get(j)[1];
                ItemStack subject = now.isEmpty() ? prev : now;
                vg$emitClick(player, worldId, pos.getX(), pos.getY(), pos.getZ(),
                        ForgeMixinBridge.itemId(subject),
                        vg$equipMeta(standType, slotNames.get(j),
                                ForgeMixinBridge.itemId(prev), ForgeMixinBridge.itemId(now)));
            }
        } catch (Throwable ignored) {
            // A mixin must never throw into the server thread.
        }
    }

    // ------------------------------------------------------------------ destruction

    /** Remember the damage source so {@code kill()} can attribute the break. */
    @Inject(
            method = "m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$beforeStandHurt(DamageSource source, float amount,
                                    CallbackInfoReturnable<Boolean> cir) {
        try {
            vg$standLastDamage = source;
            vg$standLastDamageAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
            vg$standLastDamage = null;
            vg$standLastDamageAt = 0L;
        }
    }

    /**
     * Capture the stand's persistent NBT and emit {@code ENTITY_KILL}.
     *
     * <p>NBT is read at HEAD, before the stand is removed, so the payload still
     * describes a complete entity and {@code tryRespawnEntity} can rebuild it.
     * The NBT encoder is local because the cell's {@code NbtCapture} helper is
     * package-private to the parent package and this mixin may not widen it.</p>
     */
    @Inject(
            method = "m_6074_()V",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$onStandKill(CallbackInfo ci) {
        try {
            ArmorStand self = (ArmorStand) (Object) this;
            Level level = self.level;
            if (level == null || level.isClientSide) {
                return;
            }
            DamageSource source = vg$standLastDamage;
            long sourceAt = vg$standLastDamageAt;
            vg$standLastDamage = null;
            vg$standLastDamageAt = 0L;
            // Only attribute a source recorded inside this break's call stack.
            if (source != null && (sourceAt <= 0L
                    || System.currentTimeMillis() - sourceAt > 100L)) {
                source = null;
            }

            Player player = source != null && source.getEntity() instanceof Player p ? p : null;
            var pos = self.blockPosition();
            String worldId = level.dimension().location().toString();

            Guardian g = VonixGuardianForge.guardian();
            if (g == null) {
                return;
            }
            byte[] nbt = vg$persistNbt(g) ? vg$entityNbt(self) : null;
            String sourceTag = source == null ? "#interact:armor_stand" : SourceTagger.tag(source);
            ActionBuilder b = new ActionBuilder()
                    .type(ActionType.ENTITY_KILL)
                    .actorUuid(player == null ? null : player.getUUID())
                    .actorName(player == null ? null : player.getName().getString())
                    .worldId(worldId)
                    .position(pos.getX(), pos.getY(), pos.getZ())
                    .targetId(EntitySentinel.of(self))
                    .sourceTag(sourceTag == null ? "#interact:armor_stand" : sourceTag)
                    .amount(1);
            if (nbt != null) {
                b.entityNbt(nbt);
            }
            g.submitAccepted(b.build());
        } catch (Throwable ignored) {
            // defensive: never escape into the server thread
        }
    }

    // ------------------------------------------------------------------ helpers

    @Unique
    private static boolean vg$same(ItemStack a, ItemStack b) {
        boolean aEmpty = a == null || a.isEmpty();
        boolean bEmpty = b == null || b.isEmpty();
        if (aEmpty || bEmpty) {
            return aEmpty == bEmpty;
        }
        return ItemStack.isSameItemSameTags(a, b) && a.getCount() == b.getCount();
    }

    @Unique
    private static boolean vg$persistNbt(Guardian g) {
        return g.config() != null && g.config().storage() != null
                && g.config().storage().persistNbt();
    }

    /** Serialize the stand exactly as {@code NbtCapture.entity} does. */
    @Unique
    private static byte[] vg$entityNbt(ArmorStand stand) {
        try {
            CompoundTag tag = new CompoundTag();
            if (!stand.saveAsPassenger(tag)) {
                return null;
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
                 DataOutputStream dos = new DataOutputStream(baos)) {
                NbtIo.write(tag, dos);
                dos.flush();
                byte[] bytes = baos.toByteArray();
                return bytes.length > NbtPayload.MAX_BYTES ? null : bytes;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    @Unique
    private static String vg$equipMeta(String standType, String slot, String fromItem, String toItem) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"stand\":\"").append(standType)
          .append("\",\"slot\":\"").append(slot)
          .append("\",\"from\":\"").append(fromItem)
          .append("\",\"to\":\"").append(toItem).append('}');
        return sb.toString();
    }

    @Unique
    private static void vg$emitClick(Player player, String worldId, int x, int y, int z,
                                     String targetId, String meta) {
        try {
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) {
                return;
            }
            g.submitAccepted(new ActionBuilder()
                    .type(ActionType.CLICK)
                    .actorUuid(player.getUUID())
                    .actorName(player.getName().getString())
                    .worldId(worldId)
                    .position(x, y, z)
                    .targetId(targetId)
                    .targetMeta(meta)
                    .amount(1)
                    .sourceTag("#interact:armor_stand")
                    .build());
        } catch (Throwable ignored) {
            // defensive
        }
    }
}
