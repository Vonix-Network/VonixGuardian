/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_21_1.common;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import network.vonix.guardian.core.theme.Theme;

/**
 * Renders themed text into a Minecraft {@link Component}.
 *
 * <p>All factory methods accept a nullable {@link Theme} — if {@code null}, the
 * text falls back to an unstyled literal component. Hex strings are parsed via
 * {@link TextColor#parseColor(String)}; if parsing fails the colour is dropped
 * but the literal still renders.
 */
public final class ChatRenderer {

    private ChatRenderer() {
        // utility
    }

    /**
     * Render plain text with no colour.
     *
     * @param text body text; {@code null} -&gt; empty string
     * @return a {@link MutableComponent} literal
     */
    public static MutableComponent plain(String text) {
        return Component.literal(text == null ? "" : text);
    }

    /**
     * Render text in {@link Theme#primary()}.
     */
    public static MutableComponent primary(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.primary());
    }

    /**
     * Render text in {@link Theme#secondary()}.
     */
    public static MutableComponent secondary(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.secondary());
    }

    /**
     * Render text in {@link Theme#success()}.
     */
    public static MutableComponent success(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.success());
    }

    /**
     * Render text in {@link Theme#error()}.
     */
    public static MutableComponent error(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.error());
    }

    /**
     * Render text in {@link Theme#warning()}.
     */
    public static MutableComponent warning(Theme theme, String text) {
        return styled(text, theme == null ? null : theme.warning());
    }

    /**
     * Render text in {@link Theme#muted()}.
     */
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

    /**
     * Apply an arbitrary hex colour to a literal component.
     *
     * @param text body text
     * @param hex  {@code "#RRGGBB"} or {@code null} for no colour
     * @return styled component
     */
    public static MutableComponent styled(String text, String hex) {
        MutableComponent c = Component.literal(text == null ? "" : text);
        if (hex == null || hex.isBlank()) {
            return c;
        }
        try {
            TextColor color = TextColor.parseColor(hex).result().orElse(null);
            if (color != null) {
                c.setStyle(Style.EMPTY.withColor(color));
            }
        } catch (Throwable ignored) {
            // drop colour on parse error
        }
        return c;
    }
}
