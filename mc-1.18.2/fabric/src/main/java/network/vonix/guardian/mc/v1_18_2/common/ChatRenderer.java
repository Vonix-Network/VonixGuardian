/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_18_2.common;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.TextComponent;
import network.vonix.guardian.core.theme.Theme;

/**
 * Renders themed text into a Minecraft 1.18.2 {@code MutableComponent}.
 *
 * <p>1.18.2 has no {@code Component.literal} — we construct {@link TextComponent}
 * directly.
 */
public final class ChatRenderer {

    private ChatRenderer() {
        // utility
    }

    public static MutableComponent plain(String text) {
        return new TextComponent(text == null ? "" : text);
    }

    public static MutableComponent primary(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.primary());
    }

    public static MutableComponent secondary(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.secondary());
    }

    public static MutableComponent success(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.success());
    }

    public static MutableComponent error(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.error());
    }

    public static MutableComponent warning(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.warning());
    }

    public static MutableComponent muted(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.muted());
    }

    /**
     * Bold section header for {@code /vg status} — accent color + bold.
     * Falls back gracefully when the theme is null or the accent hex is invalid.
     * @since 1.1.7
     */
    public static MutableComponent section(Theme theme, String text) {
        MutableComponent c = styled(text, theme == null ? null : theme.secondary());
        try {
            c.setStyle(c.getStyle().withBold(true));
        } catch (Throwable ignored) { }
        return c;
    }

    public static MutableComponent styled(String text, String hex) {
        MutableComponent c = new TextComponent(text == null ? "" : text);
        if (hex == null || hex.isBlank()) {
            return c;
        }
        try {
            TextColor color = TextColor.parseColor(hex);
            if (color != null) {
                c.setStyle(Style.EMPTY.withColor(color));
            }
        } catch (Throwable ignored) {
            // drop colour on parse error
        }
        return c;
    }
}
