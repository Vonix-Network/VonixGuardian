/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.common;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Brigadier {@link SuggestionProvider} factory for {@code /co}/{@code /vg}
 * filter arguments. The greedy-string filter is a single brigadier argument,
 * so we manually inspect the tail of {@link CommandContext#getInput()} to
 * decide which sub-completion list to emit (CoreProtect's tab-cycle UX).
 *
 * <p>Reference cell: {@code mc-1.20.1/forge}. Wave 2 ports may copy this file
 * verbatim — only the imports differ between loaders.
 */
public final class GuardianSuggestions {

    /** Top-level filter prefixes the user can type. */
    private static final String[] TOP_LEVEL = {
            "u:", "t:", "r:", "a:", "i:", "e:",
            "#preview", "#count", "#verbose", "#silent", "#optimize"
    };

    private static final String[] HASH_FLAGS = {
            "#preview", "#count", "#verbose", "#silent", "#optimize"
    };

    private static final String[] USER_SENTINELS = {
            "#fire", "#tnt", "#creeper", "#explosion", "#water", "#lava", "#mob"
    };

    private static final String[] TIME_VALUES = {
            "1h", "30m", "10m", "1d", "3d", "7d", "1w", "2w", "30d"
    };

    private static final String[] RADIUS_LITERALS = {
            "5", "10", "20", "50", "#global", "#worldedit", "#we"
    };

    /** Every action token CoreProtect accepts on {@code a:}. */
    private static final String[] ACTIONS = {
            "block", "+block", "-block",
            "container", "+container", "-container",
            "inventory", "+inventory", "-inventory",
            "item", "+item", "-item",
            "kill",
            "session", "+session", "-session",
            "login",
            "chat", "command", "click", "sign", "username"
    };

    /** Common block ids — starter list, full registry can be wired later. */
    private static final String[] COMMON_BLOCKS = {
            "stone", "dirt", "grass_block", "oak_log", "oak_planks", "cobblestone",
            "gravel", "sand", "glass", "tnt", "obsidian",
            "diamond_ore", "iron_ore", "gold_ore", "coal_ore",
            "chest", "barrel", "trapped_chest", "ender_chest", "shulker_box"
    };

    private GuardianSuggestions() {
        // utility
    }

    /**
     * Returns a {@link SuggestionProvider} for the greedy-string {@code filter}
     * argument used by {@code /co lookup}, {@code /co rollback}, etc. Reads the
     * last whitespace-delimited token from the raw input buffer and emits the
     * appropriate completions for its prefix.
     */
    public static SuggestionProvider<CommandSourceStack> filterTokens() {
        return GuardianSuggestions::buildSuggestions;
    }

    private static CompletableFuture<Suggestions> buildSuggestions(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String input = ctx.getInput();
        int cursor = builder.getStart();
        // What the user has typed for the current token (everything since the
        // last space, starting at builder.start). This is what we complete on.
        String token = cursor <= input.length() ? input.substring(cursor) : "";
        // Rewind suggestions builder so completions replace just `token`.
        SuggestionsBuilder local = builder.createOffset(cursor);

        Stream<String> stream = candidatesFor(token, ctx.getSource());
        return SharedSuggestionProvider.suggest(stream, local);
    }

    private static Stream<String> candidatesFor(String token, CommandSourceStack src) {
        if (token.isEmpty() || !token.contains(":")) {
            if (token.startsWith("#")) {
                return Arrays.stream(HASH_FLAGS);
            }
            return Arrays.stream(TOP_LEVEL);
        }
        // Has a colon — branch by prefix.
        int colon = token.indexOf(':');
        String head = token.substring(0, colon + 1); // includes trailing ':'
        switch (head) {
            case "u:":
                return userCandidates(token, src);
            case "t:":
                return prefixed(head, TIME_VALUES);
            case "r:":
                return radiusCandidates(token, src);
            case "a:":
                return prefixed(head, ACTIONS);
            case "i:":
            case "e:":
                return prefixed(head, COMMON_BLOCKS);
            default:
                return Stream.empty();
        }
    }

    private static Stream<String> prefixed(String head, String[] values) {
        return Arrays.stream(values).map(v -> head + v);
    }

    private static Stream<String> userCandidates(String token, CommandSourceStack src) {
        String head = "u:";
        List<String> out = new ArrayList<>();
        MinecraftServer server = src.getServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                out.add(head + p.getGameProfile().getName());
            }
        }
        for (String s : USER_SENTINELS) {
            out.add(head + s);
        }
        return out.stream();
    }

    private static Stream<String> radiusCandidates(String token, CommandSourceStack src) {
        String head = "r:";
        List<String> out = new ArrayList<>();
        for (String s : RADIUS_LITERALS) {
            out.add(head + s);
        }
        MinecraftServer server = src.getServer();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                ResourceLocation rl = level.dimension().location();
                if (rl == null) continue;
                // CoreProtect's #world_<key> uses the path component; we also
                // emit the namespaced form for non-vanilla dims.
                if ("minecraft".equals(rl.getNamespace())) {
                    out.add(head + "#world_" + rl.getPath());
                } else {
                    out.add(head + "#world_" + rl.getNamespace() + "_" + rl.getPath());
                }
            }
        }
        return out.stream();
    }
}
