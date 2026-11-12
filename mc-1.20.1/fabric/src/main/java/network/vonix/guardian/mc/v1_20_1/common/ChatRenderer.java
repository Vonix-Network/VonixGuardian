/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.mc.v1_20_1.common;

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
            TextColor color = TextColor.parseColor(hex);
            if (color != null) {
                c.setStyle(Style.EMPTY.withColor(color));
            }
        } catch (Throwable ignored) {
            // drop colour on parse error
        }
        return c;
    }

    /**
     * Colour one span and attach a hover plus either a suggested command or
     * a clipboard copy. The chat-event types differ across Minecraft versions,
     * so this looks them up. If that fails the text still shows.
     */
    public static MutableComponent interactive(Theme theme, String role, String text,
                                                String hover, String suggest, String copy) {
        MutableComponent component = styled(text, colourFor(theme, role));
        boolean hasHover = hover != null && !hover.isBlank();
        boolean hasSuggest = suggest != null && !suggest.isBlank();
        boolean hasCopy = copy != null && !copy.isBlank();
        if (!hasHover && !hasSuggest && !hasCopy) {
            return component;
        }
        try {
            Style style = component.getStyle();
            if (hasSuggest) {
                style = attach(style, "withClickEvent",
                        chatEvent("ClickEvent", "SUGGEST_COMMAND", "SuggestCommand", suggest));
            } else if (hasCopy) {
                style = attach(style, "withClickEvent",
                        chatEvent("ClickEvent", "COPY_TO_CLIPBOARD", "CopyToClipboard", copy));
            }
            if (hasHover) {
                style = attach(style, "withHoverEvent",
                        chatEvent("HoverEvent", "SHOW_TEXT", "ShowText", plain(hover)));
            }
            component.setStyle(style);
        } catch (Throwable ignored) {
            // The words still render when a version lacks the event type.
        }
        return component;
    }

    private static String colourFor(Theme theme, String role) {
        if (theme == null || role == null) {
            return null;
        }
        return switch (role) {
            case "who", "header" -> theme.primary();
            case "what" -> theme.secondary();
            case "note" -> theme.warning();
            default -> theme.muted();
        };
    }

    private static Style attach(Style style, String method, Object event) throws Exception {
        if (event == null) {
            return style;
        }
        for (java.lang.reflect.Method candidate : Style.class.getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == 1
                    && candidate.getParameterTypes()[0].isInstance(event)) {
                return (Style) candidate.invoke(style, event);
            }
        }
        return style;
    }

    private static Object chatEvent(String type, String enumName, String recordName, Object value)
            throws Exception {
        Class<?> cls = Class.forName("net.minecraft.network.chat." + type);
        for (Class<?> nested : cls.getDeclaredClasses()) {
            if (!nested.getSimpleName().equals(recordName)) {
                continue;
            }
            for (java.lang.reflect.Constructor<?> ctor : nested.getConstructors()) {
                if (ctor.getParameterCount() == 1) {
                    return ctor.newInstance(value);
                }
            }
        }
        Class<?> action;
        try {
            action = Class.forName("net.minecraft.network.chat." + type + "$Action");
        } catch (ClassNotFoundException missing) {
            return null;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object chosen = java.lang.Enum.valueOf((Class<? extends java.lang.Enum>) action, enumName);
        for (java.lang.reflect.Constructor<?> ctor : cls.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && params[0].isInstance(chosen)) {
                return ctor.newInstance(chosen, value);
            }
        }
        return null;
    }
}
