/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.mc.v1_18_2.common.WorldKey;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import network.vonix.guardian.mc.v1_18_2.forge.support.AnvilPositions;

/**
 * Records a completed anvil operation (repair, enchant merge, or rename) as an
 * {@link ActionType#CLICK} row.
 *
 * <h2>Hook choice</h2>
 * The row is emitted from {@code AnvilMenu.onTake} — the moment the player takes
 * the result item — rather than from:
 * <ul>
 *   <li>{@code AnvilBlock.use}: only proves the menu was opened, so a player who
 *       opens an anvil and walks away would produce a row.</li>
 *   <li>{@code AnvilMenu.setItemName}: the client sends one packet per keystroke,
 *       so this logs a row per typed character, and it never fires for a pure
 *       repair or enchant merge.</li>
 *   <li>{@code AnvilMenu.createResult}: recomputed on every input change, not
 *       once per completed operation.</li>
 * </ul>
 *
 * <h2>Position</h2>
 * {@code onTake} receives no position and {@code AnvilMenu} exposes none, so the
 * anvil's block position is captured from the menu's
 * {@link ContainerLevelAccess} when the menu is constructed. When that access is
 * the {@code NULL} sentinel (headless construction, e.g. a plugin opening the
 * menu directly) the row falls back to the player's own block position and
 * records {@code pos=player} in {@code targetMeta}, so the coordinate is never
 * mistaken for the anvil.
 *
 * <p>Binding: packaged Forge 1.18.2 runs the SRG-mapped
 * {@code m_142365_(Player, ItemStack)} on {@code AnvilMenu}. The three-argument
 * constructor is the delegation target of the two-argument one, so a single
 * constructor injection covers both.
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilUseMixin {

    /** {@code #interact:anvil} — player-initiated, so outside the hot-event kill-switch. */
    @Unique
    private static final String vg$anvilSource = "#interact:anvil";

    /** Anvil block position captured at menu construction; {@code null} for the NULL access. */
    @Unique
    private BlockPos vg$anvilPos;

    @Inject(
            method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void vg$captureAnvilPos(int containerId, net.minecraft.world.entity.player.Inventory inventory,
                                    ContainerLevelAccess access, CallbackInfo ci) {
        try {
            BlockPos captured = AnvilPositions.resolve(access);
            if (captured != null) {
                vg$anvilPos = captured;
            }
        } catch (Throwable ignored) {
            // Mixins must never escape into the server thread.
        }
    }

    @Inject(
            method = "m_142365_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            require = 1,
            remap = false
    )
    private void vg$onTake(Player player, ItemStack result, CallbackInfo ci) {
        try {
            if (player == null || result == null || result.isEmpty()) return;
            if (player.level == null || player.level.isClientSide) return;
            BlockPos anvil = vg$anvilPos;
            boolean atPlayer = anvil == null;
            BlockPos at = atPlayer ? player.blockPosition() : anvil;
            StringBuilder meta = new StringBuilder("cost=").append(((AnvilMenu) (Object) this).getCost());
            String renamed = null;
            try {
                if (result.hasCustomHoverName()) {
                    renamed = result.getHoverName().getString();
                }
            } catch (Throwable ignored) {
                // name unavailable — the row still records the operation
            }
            if (renamed != null) {
                meta.append(";renamed=").append(renamed);
            }
            if (atPlayer) {
                meta.append(";pos=player");
            }
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            g.submit(new ActionBuilder()
                    .type(ActionType.CLICK)
                    .actorUuid(player.getUUID())
                    .actorName(player.getName().getString())
                    .worldId(WorldKey.of(player.level))
                    .position(at.getX(), at.getY(), at.getZ())
                    .targetId(itemId(result))
                    .targetMeta(meta.toString())
                    .sourceTag(vg$anvilSource)
                    .build());
        } catch (Throwable ignored) {
            // Mixins must never escape into the server thread.
        }
    }

    /** {@code minecraft:item} id for the anvil result stack. */
    @Unique
    private static String itemId(ItemStack stack) {
        try {
            ResourceLocation rl = Registry.ITEM.getKey(stack.getItem());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }
}
