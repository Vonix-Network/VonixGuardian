/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.NbtPayload;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Comparator;
import java.util.Map;

/**
 * Shared encoding helpers for the LANE 9 interaction mixins.
 *
 * <p>Not a mixin — this file declares no {@code @Mixin} and is therefore never
 * registered in {@code vg.mixins.json}. It exists so the four interaction
 * producers agree on identifier spelling, state-property encoding, and the
 * {@code storage.persistNbt} gate instead of each carrying its own copy.</p>
 *
 * <p>Every method swallows {@link Throwable}: mixins must never escape into the
 * server thread, and a failed audit encode must degrade to "no row" rather than
 * a broken interaction.</p>
 */
final class MixinAuditSupport {

    private MixinAuditSupport() {
    }

    /** @return the live submitter, or {@code null} when the mod is not booted. */
    static EventSubmitter submitter() {
        try {
            Guardian g = VonixGuardianForge.guardian();
            return g == null ? null : g.submitter();
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return {@code true} when full NBT fidelity is enabled in the live config. */
    static boolean persistNbt() {
        try {
            Guardian g = VonixGuardianForge.guardian();
            return g != null && g.config() != null && g.config().storage() != null
                    && g.config().storage().persistNbt();
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return the canonical dimension key, never {@code null}. */
    static String worldKey(Level level) {
        try {
            ResourceLocation rl = level.dimension().location();
            return rl != null ? rl.toString() : "minecraft:overworld";
        } catch (Throwable t) {
            return "minecraft:overworld";
        }
    }

    /** @return the block registry id, or {@code null} when unresolvable. */
    static String blockId(BlockState state) {
        try {
            if (state == null) return null;
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return the item registry id, or {@code null} when empty/unresolvable. */
    static String itemId(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return null;
            ResourceLocation rl = Registry.ITEM.getKey(stack.getItem());
            return rl != null ? rl.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Encodes block-state properties as {@code key=value,key=value} with keys
     * sorted, which is exactly the form
     * {@code ForgeWorldMutator#applyMeta} decodes on the way back in. Without
     * this a rollback would restore a waxed stair facing the wrong way.
     *
     * @return the encoded string, or {@code null} when the state has no
     *         properties or encoding failed
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static String stateProps(BlockState state) {
        try {
            if (state == null) return null;
            Map<Property<?>, Comparable<?>> values = state.getValues();
            if (values.isEmpty()) return null;
            StringBuilder sb = new StringBuilder(values.size() * 12);
            values.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().getName()))
                    .forEach(e -> {
                        if (sb.length() > 0) sb.append(',');
                        String value;
                        try {
                            value = ((Property) e.getKey()).getName((Comparable) e.getValue());
                        } catch (Throwable t) {
                            value = String.valueOf(e.getValue());
                        }
                        sb.append(e.getKey().getName()).append('=').append(value);
                    });
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Serialises an item stack for the {@code item_nbt} column, honouring
     * {@code storage.persistNbt}.
     *
     * @return the NBT bytes, or {@code null} when disabled, empty, or the
     *         payload exceeds {@link NbtPayload#MAX_BYTES} (an oversized
     *         payload would make the whole action be rejected at submit)
     */
    static byte[] itemNbt(ItemStack stack) {
        try {
            if (!persistNbt() || stack == null || stack.isEmpty()) return null;
            CompoundTag tag = stack.save(new CompoundTag());
            if (tag == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                NbtIo.write(tag, dos);
                dos.flush();
            }
            byte[] bytes = baos.toByteArray();
            return bytes.length > NbtPayload.MAX_BYTES ? null : bytes;
        } catch (Throwable t) {
            return null;
        }
    }
}
