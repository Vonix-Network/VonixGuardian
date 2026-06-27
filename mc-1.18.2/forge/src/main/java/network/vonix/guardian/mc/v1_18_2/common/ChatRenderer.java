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
 * Renders themed text into a Minecraft component. On 1.18.2 there is no
 * {@code Component.literal} — we construct {@link TextComponent} directly.
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
