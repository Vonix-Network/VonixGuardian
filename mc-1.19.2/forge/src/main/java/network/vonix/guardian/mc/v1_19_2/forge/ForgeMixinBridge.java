/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_19_2.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.core.event.Sentinel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * v1.3.1 X4 — Forge-side bridge for the new hopper/portal/command mixins.
 *
 * <p>Existing Forge mixins (FireBlockMixin, LeavesBlockMixin, etc.) resolve the
 * Guardian facade inline via {@link VonixGuardianForge#guardian()}. The X4
 * mixins share more dispatch surface (5+ methods), so we consolidate them into
 * this bridge to avoid duplicating the sub()/warn() plumbing across four
 * mixins per cell.</p>
 *
 * <p>All entry points swallow {@link Throwable}: mixins must NEVER escape into
 * the server thread.</p>
 */
public final class ForgeMixinBridge {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeMixinBridge.class);

    private ForgeMixinBridge() {}

    private static EventSubmitter sub() {
        Guardian g = VonixGuardianForge.guardian();
        return g == null ? null : g.submitter();
    }

    private static String worldKey(Level level) {
        try {
            return level.dimension().location().toString();
        } catch (Throwable t) {
            return "minecraft:overworld";
        }
    }

    public static String blockId(BlockState state) {
        try {
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    public static String itemId(ItemStack stack) {
        try {
            ResourceLocation rl = Registry.ITEM.getKey(stack.getItem());
            return rl != null ? rl.toString() : "minecraft:air";
        } catch (Throwable t) {
            return "minecraft:air";
        }
    }

    // ================================================================== v1.3.1 X4 dispatchers

    /** v1.3.1 X4 — Hopper push (item moved into a container). */
    public static void hopperPush(Level level, BlockPos pos, ItemStack stack) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || stack == null || stack.isEmpty()) return;
            s.submitHopperPush(null, Sentinel.HOPPER, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), Sentinel.HOPPER);
        } catch (Throwable t) {
            warn("hopperPush", t);
        }
    }

    /** v1.3.1 X4 — Hopper pull (item moved out of a container). */
    public static void hopperPull(Level level, BlockPos pos, ItemStack stack) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || stack == null || stack.isEmpty()) return;
            s.submitHopperPull(null, Sentinel.HOPPER, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    itemId(stack), stack.getCount(), Sentinel.HOPPER);
        } catch (Throwable t) {
            warn("hopperPull", t);
        }
    }

    // portalCreate() removed in v1.3.2 Y2 — Nether portal frame creation is
    // now captured directly via BlockEvent.PortalSpawnEvent in ForgeEvents.

    /** v1.3.1 X4 — /fill or /setblock per-block old-state break row. */
    public static void commandBlockBreak(Player player, Level level, BlockPos pos,
                                         BlockState oldState, String sourceTag) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || oldState == null || oldState.isAir()) return;
            UUID uuid = player != null ? player.getUUID() : null;
            String name = player != null ? player.getName().getString() : Sentinel.COMMAND;
            s.submitBlockBreak(uuid, name, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(oldState), sourceTag != null ? sourceTag : "cmd");
        } catch (Throwable t) {
            warn("commandBlockBreak", t);
        }
    }

    /** v1.3.1 X4 — /fill or /setblock per-block new-state place row. */
    public static void commandBlockPlace(Player player, Level level, BlockPos pos,
                                         BlockState newState, String sourceTag) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null || newState == null) return;
            UUID uuid = player != null ? player.getUUID() : null;
            String name = player != null ? player.getName().getString() : Sentinel.COMMAND;
            s.submitBlockPlace(uuid, name, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(),
                    blockId(newState), sourceTag != null ? sourceTag : "cmd");
        } catch (Throwable t) {
            warn("commandBlockPlace", t);
        }
    }

    private static void warn(String label, Throwable t) {
        LOG.warn(Guardian.MARKER, "ForgeMixinBridge {} failed", label, t);
    }
}
