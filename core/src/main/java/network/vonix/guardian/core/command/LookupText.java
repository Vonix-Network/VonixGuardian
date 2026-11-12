package network.vonix.guardian.core.command;

import network.vonix.guardian.core.action.Action;
import network.vonix.guardian.core.action.ActionType;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One lookup row, as pieces a chat renderer can colour and click.
 * The words live here so every loader prints the same line.
 */
public final class LookupText {

    static final int TARGET_LIMIT = 48;
    static final int HOVER_LIMIT = 180;

    private static final DateTimeFormatter ABSOLUTE =
        DateTimeFormatter.ofPattern("d MMM HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private LookupText() {}

    /**
     * One coloured span. {@code suggest} fills the chat box with a command.
     * {@code copy} is placed on the clipboard. At most one of them is set.
     */
    public record Piece(String role, String text, String hover, String suggest, String copy) {
        public Piece {
            role = role == null ? "meta" : role;
            text = text == null ? "" : text;
        }
    }

    public static String plain(Action action, long nowMillis, boolean verbose) {
        StringBuilder out = new StringBuilder();
        for (Piece piece : line(action, nowMillis, verbose)) {
            out.append(piece.text());
        }
        return out.toString();
    }

    public static List<Piece> line(Action action, long nowMillis, boolean verbose) {
        List<Piece> pieces = new ArrayList<>(6);
        long when = action.timestamp();
        String absolute = ABSOLUTE.format(Instant.ofEpochMilli(when));
        String idCopy = action.id() >= 0L ? Long.toString(action.id()) : null;
        String idHover = action.id() >= 0L
            ? "Click to copy id " + action.id()
            : "Not stored yet";
        pieces.add(new Piece("time",
            relative(nowMillis - when) + " · " + absolute,
            idHover, null, idCopy));

        pieces.add(new Piece("who", " " + actor(action), null, null, null));
        pieces.add(new Piece("verb", " " + verb(action), null, null, null));

        String fullTarget = action.targetId() == null || action.targetId().isBlank()
            ? "?" : action.targetId();
        String shown = displayId(fullTarget);
        if (showAmount(action)) {
            shown = action.amount() + "× " + shown;
        }
        String sign = signNote(action);
        if (!sign.isEmpty() && !verbose) {
            shown = shown + " (" + sign + ")";
        }
        pieces.add(new Piece("what", " " + shown,
            clip(detailHover(action, fullTarget), HOVER_LIMIT),
            null, fullTarget));

        if (action.isPositional()) {
            String world = action.worldId() == null ? "" : action.worldId();
            String where = displayWorld(world) + " " + action.x() + ", " + action.y() + ", " + action.z();
            String suggest = teleport(world, action.x(), action.y(), action.z());
            pieces.add(new Piece("where", " · " + where,
                suggest == null ? null : "Click to fill in " + suggest,
                suggest, null));
        }
        if (action.rolledBack()) {
            pieces.add(new Piece("note", " · rolled back", null, null, null));
        }
        if (verbose) {
            String extra = verboseNote(action);
            if (!extra.isEmpty()) {
                pieces.add(new Piece("detail", " · " + extra, null, null, null));
            }
        }
        return List.copyOf(pieces);
    }

    static String relative(long deltaMs) {
        if (deltaMs < 0) deltaMs = 0;
        long seconds = deltaMs / 1000L;
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60L;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60L;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24L;
        if (days < 14) return days + "d ago";
        return (days / 7L) + "w ago";
    }

    static String displayId(String id) {
        if (id == null || id.isBlank()) return "?";
        String shown = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (shown.length() > TARGET_LIMIT) {
            return shown.substring(0, TARGET_LIMIT - 1) + "…";
        }
        return shown;
    }

    static String displayWorld(String world) {
        if (world == null || world.isBlank()) return "?";
        if (world.startsWith("minecraft:")) return world.substring("minecraft:".length());
        return world;
    }

    private static String actor(Action action) {
        String name = action.actorName();
        return name == null || name.isBlank() ? "#unknown" : name;
    }

    private static boolean showAmount(Action action) {
        if (action.amount() <= 0) return false;
        return switch (action.type()) {
            case CONTAINER_DEPOSIT, CONTAINER_WITHDRAW,
                 INVENTORY_DEPOSIT, INVENTORY_WITHDRAW,
                 HOPPER_PUSH, HOPPER_PULL,
                 ITEM_DROP, ITEM_PICKUP, ITEM_CRAFT, DISPENSE -> true;
            default -> action.amount() != 1;
        };
    }

    private static String signNote(Action action) {
        List<String> bits = new ArrayList<>(3);
        if (action.signSide() != null && !action.signSide().isBlank()) bits.add(action.signSide());
        if (action.signDyeColor() != null && !action.signDyeColor().isBlank()) bits.add(action.signDyeColor());
        if (Boolean.TRUE.equals(action.signWaxed())) bits.add("waxed");
        return String.join(", ", bits);
    }

    private static String verboseNote(Action action) {
        List<String> bits = new ArrayList<>();
        String sign = signNote(action);
        if (!sign.isEmpty()) bits.add(sign);
        if (action.inventorySlot() != null) bits.add("slot " + action.inventorySlot());
        if (action.sourceTag() != null && !action.sourceTag().isBlank()) bits.add(action.sourceTag());
        if (action.pairId() != null) bits.add("pair " + action.pairId());
        if (action.oldBlockState() != null && !action.oldBlockState().isBlank()) {
            bits.add("was " + displayId(action.oldBlockState()));
        }
        if (action.newBlockState() != null && !action.newBlockState().isBlank()) {
            bits.add("now " + displayId(action.newBlockState()));
        }
        if (action.blockEntityNbt() != null) bits.add("block data");
        if (action.itemNbt() != null) bits.add("item data");
        if (action.entityNbt() != null) bits.add("entity data");
        return String.join(", ", bits);
    }

    private static String detailHover(Action action, String fullTarget) {
        StringBuilder hover = new StringBuilder(fullTarget);
        if (showAmount(action)) hover.append(" × ").append(action.amount());
        String extra = verboseNote(action);
        if (!extra.isEmpty()) hover.append(" · ").append(extra);
        hover.append(" · click to copy");
        return hover.toString();
    }

    private static String teleport(String world, int x, int y, int z) {
        if (world == null || world.isBlank() || world.indexOf(' ') >= 0) return null;
        return "/vg tp " + world + " " + x + " " + y + " " + z;
    }

    private static String clip(String text, int limit) {
        if (text == null) return null;
        if (text.length() <= limit) return text;
        return text.substring(0, limit - 1) + "…";
    }

    static String verb(Action action) {
        ActionType type = action.type();
        if (type == null) return "changed";
        return switch (type) {
            case BLOCK_BREAK, BURN, FADE, BUCKET_FILL, LEAVES_DECAY,
                 PISTON_RETRACT, HANGING_BREAK -> "broke";
            case BLOCK_PLACE, FORM, SPREAD, IGNITE, BUCKET_EMPTY,
                 PISTON_EXTEND, HANGING_PLACE, FLUID_FLOW -> "placed";
            case CONTAINER_DEPOSIT, INVENTORY_DEPOSIT, HOPPER_PUSH -> "put";
            case CONTAINER_WITHDRAW, INVENTORY_WITHDRAW, HOPPER_PULL -> "took";
            case ITEM_DROP -> "dropped";
            case ITEM_PICKUP -> "picked up";
            case ITEM_CRAFT -> "crafted";
            case ENTITY_KILL -> "killed";
            case ENTITY_SPAWN -> "spawned";
            case ENTITY_INTERACT -> "used";
            case ENTITY_CHANGE_BLOCK -> "changed";
            case EXPLOSION -> "exploded";
            case CHAT -> "said";
            case COMMAND -> "ran";
            case SIGN -> "wrote";
            case SESSION_JOIN -> "joined";
            case SESSION_LEAVE -> "left";
            case USERNAME_CHANGE -> "renamed to";
            case DISPENSE -> "dispensed";
            case STRUCTURE_GROW -> "grew";
            case PORTAL_CREATE -> "opened a portal at";
            case CHUNK_POPULATE -> "generated";
            case CLICK -> "clicked";
        };
    }
}
