/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import network.vonix.guardian.core.Guardian;
import network.vonix.guardian.core.action.ActionBuilder;
import network.vonix.guardian.core.action.ActionType;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_18_2.common.EntitySentinel;
import network.vonix.guardian.mc.v1_18_2.common.WorldKey;
import network.vonix.guardian.mc.v1_18_2.forge.ForgeMixinBridge;
import network.vonix.guardian.mc.v1_18_2.forge.VonixGuardianForge;

import java.util.ArrayDeque;

/**
 * Shared plumbing for the lane-10 logging-coverage mixins: sponge, coral,
 * bamboo / sugar cane / chorus growth, wolf / cat taming and creeper ignition.
 *
 * <p>Not a mixin — it is never listed in {@code vg.mixins.json}. Two jobs:</p>
 *
 * <ol>
 *   <li><b>Facade resolution.</b> Every emitter returns {@code void} and swallows
 *       {@link Throwable}. These hooks run whether or not this plugin is loaded,
 *       and a mixin must never surface an exception into the vanilla server
 *       thread.</li>
 *   <li><b>Pre/post capture for random ticks.</b> Growth detection needs the state
 *       of a position <em>before</em> vanilla touched it, but one {@code @Inject}
 *       cannot carry a local between two injection points. The watch stacks below
 *       carry it instead — here, in a plain class, rather than as static fields on
 *       the mixins, so no mixin holds mutable static state.</li>
 * </ol>
 *
 * <p><b>Why every member is {@code public}.</b> Mixin merges each handler method
 * <em>into the target class</em>, so a handler written in this package ends up
 * executing as a method of {@code net.minecraft.world.level.block.SpongeBlock} —
 * a different package. Package-private access from that handler to this class
 * would compile and then fail at runtime with {@link IllegalAccessError}. Public
 * is not stylistic here; it is the only access level that survives the merge. The
 * same reason is why {@link ForgeMixinBridge} and {@link EntitySentinel} are public
 * with public static entry points.</p>
 *
 * <p>Each watch stack is a {@link ThreadLocal} because random ticks run on server
 * threads and two worlds can tick concurrently. Entries are pushed at {@code HEAD}
 * and popped at {@code RETURN} in strict LIFO order, so a nested tick cannot read
 * the outer tick's snapshot.</p>
 */
public final class MixinLoggingSupport {

    /** Actor name for natural (non-player) growth, matching the ForgeEvents sentinel convention. */
    public static final String ACTOR_NATURAL = "#natural:growth";

    /** Actor name for natural decay/conversion rows, matching the ForgeEvents {@code #natural:fade} idiom. */
    public static final String ACTOR_DECAY = "#natural:decay";

    /**
     * Cap on pending snapshots per site and thread.
     *
     * <p>Only reached when a tick throws between its {@code HEAD} and
     * {@code RETURN}, leaving a snapshot undrained. Four is enough for every site
     * (none pushes more than two), so a healthy server never evicts anything.</p>
     */
    private static final int MAX_PENDING_WATCHES = 4;

    /** Snapshot stack for {@code BambooBlock#randomTick}. */
    public static final ThreadLocal<ArrayDeque<Watch>> BAMBOO_WATCHES =
            ThreadLocal.withInitial(() -> new ArrayDeque<>());

    /** Snapshot stack for {@code SugarCaneBlock#randomTick}. */
    public static final ThreadLocal<ArrayDeque<Watch>> SUGAR_CANE_WATCHES =
            ThreadLocal.withInitial(() -> new ArrayDeque<>());

    /** Snapshot stack for {@code ChorusFlowerBlock#randomTick}. */
    public static final ThreadLocal<ArrayDeque<Watch>> CHORUS_WATCHES =
            ThreadLocal.withInitial(() -> new ArrayDeque<>());

    private MixinLoggingSupport() {}

    /**
     * One watched position and what was there before vanilla's tick ran.
     *
     * @param pos    position being watched
     * @param before state observed before the tick (may be {@code null})
     * @param fade   {@code true} → report a change here as {@code FADE} (this block
     *               converted away); {@code false} → report as {@code STRUCTURE_GROW}
     *               (a new block appeared here)
     */
    public record Watch(BlockPos pos, BlockState before, boolean fade) {}

    // ------------------------------------------------------------------ facade

    /** @return the live {@link EventSubmitter}, or {@code null} when the plugin is not ready */
    public static EventSubmitter submitter() {
        try {
            Guardian g = VonixGuardianForge.guardian();
            return g == null ? null : g.submitter();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * @param state a block state (may be {@code null})
     * @return the registry id, or {@code null} when it cannot be resolved
     */
    public static String blockId(BlockState state) {
        if (state == null) return null;
        try {
            return ForgeMixinBridge.blockId(state);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * @param level any level (may be {@code null})
     * @return the dimension key, or {@code null} when it cannot be resolved
     */
    public static String worldKey(Level level) {
        if (level == null) return null;
        try {
            return WorldKey.of(level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * @param entity the entity being described (may be {@code null})
     * @return the {@code "#mob:<ns>:<path>"} sentinel, or {@code null}
     */
    public static String entityId(Entity entity) {
        if (entity == null) return null;
        try {
            return EntitySentinel.of(entity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- emitters

    /**
     * Emit a {@code FADE} row for a block that changed kind without a player's
     * involvement — inorganic death or an auto-conversion (coral drying out, a
     * sponge absorbing water, a chorus flower ageing out).
     *
     * <p>{@code FADE}'s inverse restores the row's {@code targetId} verbatim, so
     * {@code before} is what a rollback puts back. Only a change of block
     * <em>kind</em> is reported: a property-only change returns silently, which is
     * what keeps coral waterlogging flips and age counters out of the log.</p>
     *
     * @param level  world the change happened in
     * @param pos    changed block position
     * @param before pre-change state
     * @param after  post-change state (may be {@code null} when unreadable)
     * @param tag    source tag classifier, e.g. {@code "#coral:die"}
     */
    public static void emitFade(Level level, BlockPos pos, BlockState before, BlockState after, String tag) {
        try {
            if (level == null || level.isClientSide || pos == null || before == null || after == null) return;
            if (before.getBlock() == after.getBlock()) return;
            String oldId = blockId(before);
            if (oldId == null) return;
            EventSubmitter s = submitter();
            if (s == null) return;
            s.submitFade(null, ACTOR_DECAY, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(), oldId, tag);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Emit a {@code STRUCTURE_GROW} row for a block that appeared because a plant
     * grew, not because anyone placed it.
     *
     * <p>{@code STRUCTURE_GROW}'s inverse clears the cell, so {@code after} must be
     * the state now occupying {@code pos}. A property-only change is ignored for the
     * same reason as {@link #emitFade}: an age bump is not a placement.</p>
     *
     * @param level  world the growth happened in
     * @param pos    position of the newly grown block
     * @param before state at {@code pos} before growth (may be {@code null})
     * @param after  state at {@code pos} after growth
     * @param tag    source tag classifier, e.g. {@code "#natural:bamboo"}
     */
    public static void emitGrowth(Level level, BlockPos pos, BlockState before, BlockState after, String tag) {
        try {
            if (level == null || level.isClientSide || pos == null || after == null) return;
            if (before != null && before.getBlock() == after.getBlock()) return;
            String newId = blockId(after);
            if (newId == null) return;
            EventSubmitter s = submitter();
            if (s == null) return;
            s.submitStructureGrow(null, ACTOR_NATURAL, worldKey(level),
                    pos.getX(), pos.getY(), pos.getZ(), newId, tag);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Record a player interaction whose only durable trace is the interaction
     * itself: taming a wolf or cat, igniting a creeper with flint and steel.
     *
     * <p>Emits {@link ActionType#CLICK} — the type this wave wires shut a
     * declared-but-never-produced hole. CLICK is deliberately non-rollbackable:
     * these acts change entity state, not world blocks, so the rollback planner
     * counts them as skipped with a stated reason instead of attempting a mutation
     * it cannot verify.</p>
     *
     * <p>{@code targetId} is the {@code "#mob:<ns>:<path>"} sentinel so a lookup by
     * entity works, and {@code sourceTag} names the specific act so an operator can
     * tell "tamed a wolf" from "ignited a creeper".</p>
     *
     * @param entity entity that was interacted with
     * @param player the player who acted
     * @param tag    source tag classifier, e.g. {@code "#tame:wolf"}
     */
    public static void emitInteraction(Entity entity, net.minecraft.world.entity.player.Player player, String tag) {
        try {
            if (entity == null || player == null || entity.level == null) return;
            if (entity.level.isClientSide) return;
            String type = entityId(entity);
            if (type == null) return;
            Guardian g = VonixGuardianForge.guardian();
            if (g == null) return;
            BlockPos pos = entity.blockPosition();
            g.submit(new ActionBuilder()
                    .type(ActionType.CLICK)
                    .actorUuid(player.getUUID())
                    .actorName(player.getName().getString())
                    .worldId(worldKey(entity.level))
                    .position(pos.getX(), pos.getY(), pos.getZ())
                    .targetId(type)
                    .sourceTag(tag)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------- tick watches

    /**
     * Snapshot {@code pos} onto a watch stack before vanilla's tick runs.
     *
     * @param stack the site's stack
     * @param level world to read from; {@code null} pushes nothing
     * @param pos   position to watch; {@code null} pushes nothing
     * @param fade  report a change here as {@code FADE} rather than {@code STRUCTURE_GROW}
     */
    public static void pushWatch(ThreadLocal<ArrayDeque<Watch>> stack, Level level, BlockPos pos, boolean fade) {
        if (stack == null || level == null || pos == null) return;
        try {
            ArrayDeque<Watch> q = stack.get();
            // A tick that throws between HEAD and RETURN never reaches its drain,
            // so its snapshot would sit at the bottom of the stack forever. The cap
            // keeps that bounded: past it we drop the oldest entries, which can only
            // cost a missed row for a tick that already failed.
            while (q.size() >= MAX_PENDING_WATCHES) {
                q.pollLast();
            }
            q.push(new Watch(pos.immutable(), level.getBlockState(pos), fade));
        } catch (Throwable ignored) {
            // A failed snapshot leaves no half-pushed entry: the matching pop in
            // drainWatches finds the stack empty and emits nothing.
        }
    }

    /**
     * Pop this tick's snapshots and emit one row per genuine change of block kind.
     *
     * <p>{@code count} is the compile-time constant from the calling mixin, so a
     * tick that pushed two snapshots always attempts two pops — a push that failed
     * cannot desynchronise the stack for later ticks.</p>
     *
     * @param stack the site's stack
     * @param level world to re-read
     * @param count how many entries this tick pushed
     * @param tag   source tag classifier for the emitted rows
     */
    public static void drainWatches(ThreadLocal<ArrayDeque<Watch>> stack, Level level, int count, String tag) {
        if (stack == null) return;
        ArrayDeque<Watch> q = stack.get();
        try {
            for (int i = 0; i < count; i++) {
                Watch w = q.poll();
                if (w == null) return;
                if (w.pos() == null) continue;
                if (level == null) continue;
                BlockState after = level.getBlockState(w.pos());
                if (w.fade()) {
                    emitFade(level, w.pos(), w.before(), after, tag);
                } else {
                    emitGrowth(level, w.pos(), w.before(), after, tag);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (q.isEmpty()) {
                stack.remove();
            }
        }
    }
}
