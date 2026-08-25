package network.vonix.guardian.core.action;

/**
 * Converts a command payload into the minimal audit-safe representation.
 * Only the first command token is retained; arguments are never persisted.
 */
public final class CommandPayloadSanitizer {

    private CommandPayloadSanitizer() {}

    /**
     * Retains a canonical leading slash plus the first token. A null, blank,
     * slash-only, or quote-only input becomes the empty string.
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.isEmpty()) return "";
        while (value.startsWith("/")) {
            value = value.substring(1).trim();
        }
        if (value.isEmpty()) return "";

        String token;
        char first = value.charAt(0);
        if (first == '\'' || first == '"') {
            int closing = value.indexOf(first, 1);
            token = closing > 1 ? value.substring(1, closing) : "";
            int whitespace = firstWhitespace(token);
            if (whitespace >= 0) token = token.substring(0, whitespace);
        } else {
            int end = 0;
            while (end < value.length() && !Character.isWhitespace(value.charAt(end))) {
                end++;
            }
            token = value.substring(0, end);
        }
        token = token.trim();
        return token.isEmpty() ? "" : "/" + token;
    }

    /**
     * Enforce command privacy at the final persistence boundary. Non-command
     * actions are returned unchanged; command actions are rebuilt with every
     * field preserved except {@code targetId}.
     */
    public static Action sanitizeForPersistence(Action action) {
        if (action == null || action.type() != ActionType.COMMAND) {
            return action;
        }
        String targetId = sanitize(action.targetId());
        if (java.util.Objects.equals(targetId, action.targetId())) {
            return action;
        }
        return new Action(action.id(), action.timestamp(), action.type(), action.actorUuid(),
                action.actorName(), action.worldId(), action.x(), action.y(), action.z(),
                targetId, action.targetMeta(), action.amount(), action.rolledBack(),
                action.sourceTag(), action.signSide(), action.signDyeColor(), action.signWaxed(),
                action.oldBlockState(), action.newBlockState(), action.blockEntityNbt(),
                action.itemNbt(), action.entityNbt(), action.pairId(), action.inventorySlot());
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }
}
