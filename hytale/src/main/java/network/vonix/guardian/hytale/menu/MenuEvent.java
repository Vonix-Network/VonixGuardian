/*
 * Copyright (c) 2026 Vonix Network
 * Licensed under the MIT License.
 */
package network.vonix.guardian.hytale.menu;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Payload decoded from a custom-UI data event.
 *
 * <p>The codec key is {@code SearchQuery}. The {@code @} prefix is only the
 * client substitution marker on the event binding, matching
 * {@code CommandListPage}.
 */
public final class MenuEvent {

    static final String ACTION = "Action";
    static final String SEARCH_QUERY = "SearchQuery";

    public static final BuilderCodec<MenuEvent> CODEC = BuilderCodec.builder(MenuEvent.class, MenuEvent::new)
            .append(new KeyedCodec<>(ACTION, Codec.STRING),
                    (MenuEvent event, String value) -> event.action = value,
                    (MenuEvent event) -> event.action)
            .add()
            .append(new KeyedCodec<>(SEARCH_QUERY, Codec.STRING),
                    (MenuEvent event, String value) -> event.searchQuery = value,
                    (MenuEvent event) -> event.searchQuery)
            .add()
            .build();

    private String action;
    private String searchQuery;

    public MenuEvent() {
    }

    String action() {
        return action;
    }

    String searchQuery() {
        return searchQuery;
    }
}
