/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.command;

import network.vonix.guardian.core.action.Action;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text lookup lines. The Minecraft lane wraps the same shape in chat
 * components; Hytale sends each line as a {@code Message}.
 */
final class LookupText {

    private LookupText() {
    }

    static List<String> page(List<Action> rows, int page, int pageSize, long now,
                             String filter, boolean hasNext) {
        int current = Math.max(1, page);
        List<String> out = new ArrayList<>(rows.size() + 2);
        out.add("[VonixGuardian] Lookup: page " + current + (hasNext ? " (more results)" : ""));
        for (Action action : rows) {
            out.add(line(action, now));
        }
        if (hasNext) {
            out.add(footer(current + 1, pageSize, filter));
        }
        return out;
    }

    static String line(Action action, long now) {
        StringBuilder text = new StringBuilder(96);
        text.append(ago(now - action.timestamp()))
                .append(' ')
                .append(safeName(action.actorName()))
                .append(' ')
                .append(verb(action))
                .append(' ')
                .append(safe(action.targetId()))
                .append(" (")
                .append(action.x()).append(',').append(action.y()).append(',').append(action.z())
                .append(')');
        if (action.inventorySlot() != null) {
            text.append(" slot=").append(action.inventorySlot());
        }
        if (action.rolledBack()) {
            text.append(" [rolled back]");
        }
        return text.toString();
    }

    private static String footer(int nextPage, int pageSize, String filter) {
        String pageToken = pageSize == 10
                ? Integer.toString(nextPage)
                : nextPage + ":" + pageSize;
        String rest = filter == null ? "" : filter.trim();
        String command = rest.isEmpty()
                ? "/guardian lookup " + pageToken
                : "/guardian lookup " + pageToken + " " + rest;
        return "  >> Next page: " + command + "   (alias: /vg lookup)";
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    private static String safeName(String value) {
        return value == null || value.isBlank() ? "#unknown" : value;
    }

    private static String verb(Action action) {
        return switch (action.type()) {
            case BLOCK_BREAK, BURN, FADE, BUCKET_FILL, LEAVES_DECAY,
                    PISTON_RETRACT, HANGING_BREAK -> "broke";
            case BLOCK_PLACE, FORM, SPREAD, IGNITE, BUCKET_EMPTY,
                    PISTON_EXTEND, HANGING_PLACE, FLUID_FLOW -> "placed";
            case CONTAINER_DEPOSIT, INVENTORY_DEPOSIT, HOPPER_PUSH -> "deposited";
            case CONTAINER_WITHDRAW, INVENTORY_WITHDRAW, HOPPER_PULL -> "withdrew";
            case ITEM_DROP -> "dropped";
            case ITEM_PICKUP -> "picked up";
            case ITEM_CRAFT -> "crafted";
            case ENTITY_KILL -> "killed";
            case ENTITY_SPAWN -> "spawned";
            case ENTITY_INTERACT -> "interacted with";
            case ENTITY_CHANGE_BLOCK -> "changed block";
            case EXPLOSION -> "exploded";
            case CHAT -> "said";
            case COMMAND -> "ran";
            case SIGN -> "signed";
            case SESSION_JOIN -> "joined";
            case SESSION_LEAVE -> "left";
            case USERNAME_CHANGE -> "renamed";
            case DISPENSE -> "dispensed";
            case STRUCTURE_GROW -> "grew";
            case PORTAL_CREATE -> "created portal";
            case CHUNK_POPULATE -> "populated";
            case CLICK -> "clicked";
        };
    }

    private static String ago(long deltaMs) {
        if (deltaMs < 0) {
            deltaMs = 0;
        }
        long seconds = deltaMs / 1000L;
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60L;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60L;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24L;
        if (days < 14) {
            return days + "d ago";
        }
        return (days / 7L) + "w ago";
    }
}
