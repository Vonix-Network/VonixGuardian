/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.forge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import network.vonix.guardian.mc.v1_18_2.forge.support.BlockWriteHook;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every block change at the one place they all must pass through.
 *
 * <p><b>Why this exists.</b> Until now VonixGuardian recorded what game <em>events</em>
 * told it about, and an event only fires for the paths vanilla chose to instrument.
 * Any mod that writes blocks directly — a dragon breathing fire, a modded explosion,
 * a custom machine, a ritual, a teleport that swaps terrain — never raises those
 * events and so was invisible. That is not a bug in any single hook; it is the
 * strategy. A griefing tool that only sees vanilla's paths is not an audit tool.</p>
 *
 * <p><b>How this fixes it.</b> {@code LevelChunk.setBlockState} is the chokepoint:
 * every block write in the game ends here, whoever initiates it. Injecting at
 * {@code HEAD} and comparing the state that was there against the state being written
 * catches the whole class of them, including mods that do not know we exist. This is
 * the approach Ledger takes, and it is why Ledger attributes a ridden dragon's blast
 * without a dragon-specific hook.</p>
 *
 * <p><b>Cost, stated honestly.</b> This runs on every block write, including the ones
 * the event layer already reported. The work per call is one {@code getBlockState}
 * plus a reference comparison, and a no-op return when the state is unchanged —
 * {@code setBlockState} is called for no-op writes too, and those must not produce
 * rows. Attribution is not attempted here: this hooking layer only knows that a
 * position changed. {@link BlockWriteHook} decides what, if anything, to record, and
 * defers to the existing per-type attribution when a richer source is available.</p>
 */
@Mixin(LevelChunk.class)
public abstract class BlockWriteMixin {

    /**
     * {@code LevelChunk.level} is SRG {@code f_62776_} at runtime.
     *
     * <p>{@code remap = false} with the SRG name, matching
     * {@code ServerGamePacketListenerAccessor}: this build's refmap carries almost no
     * entries, so a named {@code level} lookup cannot resolve and the mixin fails to
     * apply. That is not a warning — it aborts boot, which is exactly what happened on
     * a live server the first time this shipped.</p>
     *
     * <p>The declared type must match the target exactly. The field is
     * {@link Level}, not {@link ServerLevel}; casting at the use site keeps the
     * shadow bindable.</p>
     */
    @Shadow(remap = false)
    @Final
    private net.minecraft.world.level.Level f_62776_;

    /**
     * Capture the state being replaced before the write lands.
     *
     * <p>The descriptor is required: {@code setBlockState} has a two-argument overload
     * and an {@code isMoving} variant, and an unqualified name cannot be resolved
     * against both. Naming the signature also makes a future mapping change fail at
     * mixin-apply time rather than silently selecting the wrong overload.</p>
     *
     * @param pos      position being written
     * @param state    the state being written
     * @param isMoving unused by this hook; present because it is part of the target
     *                 signature and the injector must match it
     * @param cir      return-value handle; never modified, so vanilla behaviour is
     *                 unchanged in every case
     */
    @Inject(
        method = "m_6978_(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"), require = 1, remap = false)
    private void vg$observeBlockWrite(BlockPos pos, BlockState state, boolean isMoving,
                                      CallbackInfoReturnable<BlockState> cir) {
        try {
            if (this.f_62776_ instanceof ServerLevel serverLevel) {
                BlockWriteHook.observe(serverLevel, pos, state);
            }
        } catch (Throwable ignored) {
            // An audit hook must never break the world write it is observing. A
            // throw here would surface inside whatever mod or vanilla path called
            // setBlockState, which is a far worse outcome than a missing row.
        }
    }
}
