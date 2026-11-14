/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
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
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Map;

/**
 * Shared encoding helpers for the LANE 9 interaction mixins (honeycomb wax,
 * axe wax-off, beds, lecterns).
 *
 * <p>Not a mixin — this file declares no {@code @Mixin} and is deliberately not
 * listed in {@code vg.mixins.json}. It carries the identifier spelling,
 * state-property encoding, and {@code storage.persistNbt} gate that the four
 * interaction producers must agree on.</p>
 *
 * <p><b>Why every member is {@code public}.</b> Mixin merges each handler method
 * <em>into the target class</em>, so a handler written in this package executes
 * as a method of {@code net.minecraft.world.item.HoneycombItem} — a different
 * package. A package-private call from that handler compiles here and then fails
 * at runtime with {@link IllegalAccessError}, because the JVM resolves the call
 * against the merged class, not against the mixin source. Public is not
 * stylistic; it is the only access level that survives the merge.</p>
 *
 * <p>Every method swallows {@link Throwable}: mixins must never escape into the
 * server thread, and a failed audit encode must degrade to "no row" rather than
 * a broken interaction.</p>
 */
public final class MixinAuditSupport {

    /**
     * Cap on nested {@code useOn} snapshots per thread. Only reached when a call
     * throws between its HEAD and RETURN, leaving a snapshot undrained; four is
     * deeper than any legitimate nesting, so a healthy server never evicts.
     */
    private static final int MAX_USE_DEPTH = 4;

    /**
     * Pre-{@code useOn} block states, per thread, LIFO.
     *
     * <p>Held here rather than as a static field on the mixin: mixin merges a
     * field into the target class, so per-mixin static state multiplies across
     * target classes and cannot be reasoned about as one stash. A plain class
     * keeps the capture/consume pair in one place for both item mixins, and
     * gives a bounded stack instead of a single slot a nested call would
     * overwrite. {@code ArrayDeque} is used with a holder record because it
     * rejects {@code null} elements and an unreadable pre-state is legitimate.</p>
     */
    private static final ThreadLocal<ArrayDeque<UseSnapshot>> USE_BEFORE =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** One captured pre-interaction state. */
    private record UseSnapshot(BlockState before) { }

    /**
     * Player currently driving a {@code LecternMenu} "take book" click, per
     * thread.
     *
     * <p>The lectern's book lives in a private container adapter inside
     * {@code LecternBlockEntity}, which knows the block position but never sees
     * the player. The menu button handler is the opposite: it has the player but
     * not the position. The adapter's removal runs synchronously inside the
     * menu call, so the menu publishes the acting player here, the adapter
     * consumes it, and the row carries real attribution instead of a sentinel.
     * Hopper or mod-driven removals never go through the menu, so they fall back
     * to the {@code #lectern} sentinel — which is the honest answer for them.</p>
     */
    private static final ThreadLocal<Player> LECTERN_TAKER =
            new ThreadLocal<>();

    private MixinAuditSupport() {
    }

    /**
     * Publishes the player for the in-flight lectern take. Clears first so a
     * previous call that threw before its RETURN cannot attribute this take to
     * the wrong player.
     *
     * @param player acting player; {@code null} clears without publishing
     */
    public static void pushLecternTaker(Player player) {
        try {
            LECTERN_TAKER.remove();
            if (player != null) {
                LECTERN_TAKER.set(player);
            }
        } catch (Throwable ignored) {
            // no attribution rather than wrong attribution
        }
    }

    /** Drops the published lectern player. Safe to call when none was published. */
    public static void clearLecternTaker() {
        try {
            LECTERN_TAKER.remove();
        } catch (Throwable ignored) {
        }
    }

    /** @return the player driving the in-flight lectern take, or {@code null} */
    public static Player lecternTaker() {
        try {
            return LECTERN_TAKER.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Captures the block state at {@code pos} before a {@code useOn} runs.
     * Always pushes exactly one entry, including when the state cannot be read,
     * so the matching {@link #popUseBefore()} is always balanced.
     *
     * @param level world to read; {@code null} pushes an empty snapshot
     * @param pos   position to read; {@code null} pushes an empty snapshot
     */
    public static void pushUseBefore(Level level, BlockPos pos) {
        try {
            ArrayDeque<UseSnapshot> stack = USE_BEFORE.get();
            while (stack.size() >= MAX_USE_DEPTH) {
                stack.pollLast();
            }
            BlockState before = null;
            try {
                if (level != null && pos != null) {
                    before = level.getBlockState(pos);
                }
            } catch (Throwable ignored) {
                // an unreadable pre-state yields null, which callers treat as "skip"
            }
            stack.push(new UseSnapshot(before));
        } catch (Throwable ignored) {
            // A failed push leaves the stack unchanged; the matching pop returns
            // null and the producer emits nothing for this interaction.
        }
    }

    /**
     * Pops the snapshot captured by the matching {@link #pushUseBefore}.
     *
     * @return the pre-interaction state, or {@code null} when none was captured
     *         or the state was unreadable
     */
    public static BlockState popUseBefore() {
        try {
            ArrayDeque<UseSnapshot> stack = USE_BEFORE.get();
            UseSnapshot snap = stack.poll();
            if (stack.isEmpty()) {
                USE_BEFORE.remove();
            }
            return snap == null ? null : snap.before();
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return the live submitter, or {@code null} when the mod is not booted. */
    public static EventSubmitter submitter() {
        try {
            Guardian g = VonixGuardianForge.guardian();
            return g == null ? null : g.submitter();
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return {@code true} when full NBT fidelity is enabled in the live config. */
    public static boolean persistNbt() {
        try {
            Guardian g = VonixGuardianForge.guardian();
            return g != null && g.config() != null && g.config().storage() != null
                    && g.config().storage().persistNbt();
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return the canonical dimension key, never {@code null}. */
    public static String worldKey(Level level) {
        try {
            ResourceLocation rl = level.dimension().location();
            return rl != null ? rl.toString() : "minecraft:overworld";
        } catch (Throwable t) {
            return "minecraft:overworld";
        }
    }

    /** @return the block registry id, or {@code null} when unresolvable. */
    public static String blockId(BlockState state) {
        try {
            if (state == null) return null;
            ResourceLocation rl = Registry.BLOCK.getKey(state.getBlock());
            return rl != null ? rl.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return the item registry id, or {@code null} when empty/unresolvable. */
    public static String itemId(ItemStack stack) {
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
     * sorted — exactly the form {@code ForgeWorldMutator#applyMeta} decodes on
     * the way back in. Without this a rollback restores the block default and
     * drops a waxed stair's facing, half, and waterlogging.
     *
     * @return the encoded string, or {@code null} when the state has no
     *         properties or encoding failed
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String stateProps(BlockState state) {
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
     * <p>An oversized payload is dropped rather than truncated: {@code Guardian}
     * rejects any action whose NBT exceeds {@link NbtPayload#MAX_BYTES}
     * outright, so returning it would cost the whole row instead of just its
     * NBT. A row without NBT is still a usable audit trail; a rejected row is
     * no row at all.</p>
     *
     * @return the NBT bytes, or {@code null} when disabled, empty, unencodable,
     *         or over the cap
     */
    public static byte[] itemNbt(ItemStack stack) {
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
