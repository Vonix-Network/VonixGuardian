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
