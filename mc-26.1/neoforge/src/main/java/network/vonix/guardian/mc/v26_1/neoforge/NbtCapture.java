/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v26_1.neoforge;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
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
 * v1.3.2 Y1: server-thread NBT capture helpers.
 *
 * <p>All methods are invoked on the server tick thread from the producer
 * events (BreakEvent, container change, ItemToss, LivingDeath) because those
 * events fire synchronously on tick. NBT snapshotting of ItemStack / Entity /
 * BlockEntity is only safe on the server thread. Encoded byte[] payloads are
 * then handed off to the async write queue.</p>
 *
 * <p>Every method catches Throwable and returns {@code null} on failure so a
 * broken mod's NBT never blocks event submission.</p>
 */
final class NbtCapture {

    private static final Logger LOG = LoggerFactory.getLogger(NbtCapture.class);

    /** Absolute per-payload cap. Anything past this yields null. */
    static final int MAX_NBT_BYTES = 512 * 1024; // 512 KiB

    private NbtCapture() {}

    /**
     * Encode a BlockEntity's persistent NBT (chest contents, spawner data,
     * sign back text, etc.) for a soon-to-be-broken block.
     */
    static byte[] blockEntity(BlockEntity be, HolderLookup.Provider registries) {
        if (be == null || registries == null) return null;
        try {
            CompoundTag tag = be.saveCustomOnly(registries);
            return write(tag);
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "blockEntity NBT capture failed: {}", t.toString());
            return null;
        }
    }

    /**
     * Encode a block-state property string in the v5 compact
     * {@code k=v,k=v} form. Returns {@code null} if the state has no
     * user-facing properties.
     */
    static String blockStateProps(BlockState state) {
        if (state == null) return null;
        try {
            java.util.List<Property.Value<?>> values = state.getValues().toList();
            if (values.isEmpty()) return null;
            StringBuilder sb = new StringBuilder(values.size() * 12);
            values.stream()
                    .sorted(Comparator.comparing(v -> v.property().getName()))
                    .forEach(v -> {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(v.property().getName()).append('=').append(propValue(v.property(), v.value()));
                    });
            return sb.toString();
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "blockStateProps encode failed: {}", t.toString());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propValue(Property property, Comparable value) {
        try {
            return property.getName(value);
        } catch (Throwable t) {
            return String.valueOf(value);
        }
    }

    /** Encode an ItemStack (name, enchants, damage, components…) or return null on failure. */
    static byte[] itemStack(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) return null;
        try {
            RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
            Tag tag = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            if (!(tag instanceof CompoundTag ct)) return null;
            return write(ct);
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "itemStack NBT capture failed: {}", t.toString());
            return null;
        }
    }

    /**
     * Encode an entity's persistent NBT via {@code Entity.saveAsPassenger}
     * so the entity type id is included and {@code loadEntityRecursive} can
     * round-trip it.
     */
    static byte[] entity(Entity entity) {
        if (entity == null) return null;
        try {
            // 26.1: saveAsPassenger takes ValueOutput; build a CompoundTag via TagValueOutput.
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
            if (!entity.saveAsPassenger(out)) return null;
            return write(out.buildResult());
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "entity NBT capture failed: {}", t.toString());
            return null;
        }
    }

    private static byte[] write(CompoundTag tag) {
        if (tag == null) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
             DataOutputStream dos = new DataOutputStream(baos)) {
            NbtIo.write(tag, dos);
            dos.flush();
            byte[] bytes = baos.toByteArray();
            if (bytes.length > MAX_NBT_BYTES) {
                LOG.debug(Guardian.MARKER, "NBT payload {} bytes exceeds cap {}, discarding", bytes.length, MAX_NBT_BYTES);
                return null;
            }
            return bytes;
        } catch (Throwable t) {
            LOG.debug(Guardian.MARKER, "NbtIo.write failed: {}", t.toString());
            return null;
        }
    }
}
