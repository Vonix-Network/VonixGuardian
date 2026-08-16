package network.vonix.threadedhorizons.common.command;

import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.diagnostics.RuntimeDiagnostics;
import network.vonix.threadedhorizons.common.notickvd.IChunkTicketManager;
import network.vonix.threadedhorizons.common.util.FilteringIterable;
import network.vonix.threadedhorizons.mixin.access.IServerChunkManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.longs.LongSet;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.TextComponent;

public class ThreadedHorizonsCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var node = dispatcher.register(
                Commands.literal("threadedhorizons")
                        .then(
                                Commands.literal("notick")
                                        .requires(unused -> ThreadedHorizonsConfig.noTickViewDistanceConfig.enabled)
                                        .executes(ThreadedHorizonsCommands::noTickCommand)
                        )
                        .then(
                                Commands.literal("status")
                                        .executes(ThreadedHorizonsCommands::statusCommand)
                        )
                        .then(
                                Commands.literal("debug")
                                        .requires(unused -> LoaderHooks.isDevelopmentEnvironment())
                                        .then(
                                                Commands.literal("mobcaps")
                                                        .requires(unused -> ThreadedHorizonsConfig.noTickViewDistanceConfig.enabled)
                                                        .executes(ThreadedHorizonsCommands::mobcapsCommand)
                                        )
                        )
        );
        dispatcher.register(Commands.literal("th").redirect(node));
    }

    private static int statusCommand(CommandContext<CommandSourceStack> ctx) {
        String line = RuntimeDiagnostics.formatLine();
        ctx.getSource().sendSuccess(new TextComponent(line), true);
        return (int) Math.min(Integer.MAX_VALUE, RuntimeDiagnostics.uncaughtExceptions() + RuntimeDiagnostics.rejectedTasks());
    }

    private static int noTickCommand(CommandContext<CommandSourceStack> ctx) {
        final ServerChunkCache chunkManager = ctx.getSource().getLevel().getChunkSource();
        final DistanceManager distanceManager = ((IServerChunkManager) chunkManager).getDistanceManager();
        final int noTickOnlyChunks = ((IChunkTicketManager) distanceManager).getNoTickOnlyChunks().size();
        final int noTickPendingTicketUpdates = ((IChunkTicketManager) distanceManager).getNoTickPendingTicketUpdates();
        ctx.getSource().sendSuccess(new TextComponent(String.format("No-tick chunks: %d", noTickOnlyChunks)), true);
        ctx.getSource().sendSuccess(new TextComponent(String.format("No-tick chunk pending ticket updates: %d", noTickPendingTicketUpdates)), true);

        return 0;
    }

    private static int mobcapsCommand(CommandContext<CommandSourceStack> ctx) {
        final ServerLevel serverWorld = ctx.getSource().getLevel();
        final ServerChunkCache chunkManager = serverWorld.getChunkSource();
        final DistanceManager distanceManager = ((IServerChunkManager) chunkManager).getDistanceManager();
        final LongSet noTickOnlyChunks = ((IChunkTicketManager) distanceManager).getNoTickOnlyChunks();
        final Iterable<Entity> iterable;
        if (noTickOnlyChunks == null) {
            iterable = serverWorld.getAllEntities();
        } else {
            iterable = new FilteringIterable<>(serverWorld.getAllEntities(), entity -> !noTickOnlyChunks.contains(entity.chunkPosition().toLong()));
        }

        ctx.getSource().sendSuccess(new TextComponent("Mobcap details"), true);
        for (Entity entity : iterable) {
            if (entity instanceof Mob mobEntity) {
                ctx.getSource().sendSuccess(new TextComponent(String.format("%s: ", mobEntity.getType().getCategory().getName())).append(mobEntity.getDisplayName()).append(String.format(" in %s", mobEntity.chunkPosition())), true);
            }
        }
        return 0;
    }

}
