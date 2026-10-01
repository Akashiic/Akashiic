package org.bukkit.event;

import java.util.Objects;

public final class HandlerList {
    private HandlerList() {
    }

    public static void unregisterAll(Listener listener) {
        Objects.requireNonNull(listener, "listener");
    }
}
