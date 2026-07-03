/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import network.vonix.guardian.core.Guardian;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Comparator;
import java.util.Map;

/**
 * v1.3.2 Y1: server-thread NBT capture helpers (pre-1.21 flavor).
 */
final class NbtCapture {
    private static final Logger LOG = LoggerFactory.getLogger(NbtCapture.class);
    static final int MAX_NBT_BYTES = 512 * 1024;
    private NbtCapture() {}

    static byte[] blockEntity(BlockEntity be) {
        if (be == null) return null;
        try { return write(be.saveWithoutMetadata()); }
        catch (Throwable t) { LOG.debug(Guardian.MARKER, "blockEntity NBT capture failed: {}", t.toString()); return null; }
    }

    static String blockStateProps(BlockState state) {
        if (state == null) return null;
        try {
            Map<Property<?>, Comparable<?>> values = state.getValues();
            if (values.isEmpty()) return null;
            StringBuilder sb = new StringBuilder(values.size() * 12);
            values.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().getName()))
                    .forEach(e -> {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(e.getKey().getName()).append('=').append(propValue(e.getKey(), e.getValue()));
                    });
            return sb.toString();
        } catch (Throwable t) { LOG.debug(Guardian.MARKER, "blockStateProps encode failed: {}", t.toString()); return null; }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propValue(Property property, Comparable value) {
        try { return property.getName(value); } catch (Throwable t) { return String.valueOf(value); }
    }

    static byte[] itemStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        try { return write(stack.save(new CompoundTag())); }
        catch (Throwable t) { LOG.debug(Guardian.MARKER, "itemStack NBT capture failed: {}", t.toString()); return null; }
    }

    static byte[] entity(Entity entity) {
        if (entity == null) return null;
        try {
            CompoundTag tag = new CompoundTag();
            if (!entity.saveAsPassenger(tag)) return null;
            return write(tag);
        } catch (Throwable t) { LOG.debug(Guardian.MARKER, "entity NBT capture failed: {}", t.toString()); return null; }
    }

    private static byte[] write(CompoundTag tag) {
        if (tag == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
             DataOutputStream dos = new DataOutputStream(baos)) {
            NbtIo.write(tag, dos);
            dos.flush();
            byte[] bytes = baos.toByteArray();
            if (bytes.length > MAX_NBT_BYTES) {
                LOG.debug(Guardian.MARKER, "NBT payload {} bytes exceeds cap {}", bytes.length, MAX_NBT_BYTES);
                return null;
            }
            return bytes;
        } catch (Throwable t) { LOG.debug(Guardian.MARKER, "NbtIo.write failed: {}", t.toString()); return null; }
    }
}
