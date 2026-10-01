package me.neznamy.tab.api;

import java.util.UUID;

/** Minimal public TAB API fixture used by the reflection-adapter tests. */
public final class TabAPI {
    private static volatile TabAPI instance;

    private final Object player;

    public TabAPI(Object player) {
        this.player = player;
    }

    public static TabAPI getInstance() {
        return instance;
    }

    public static void setInstance(TabAPI replacement) {
        instance = replacement;
    }

    public Object getPlayer(UUID playerId) {
        return playerId == null ? null : player;
    }
}
