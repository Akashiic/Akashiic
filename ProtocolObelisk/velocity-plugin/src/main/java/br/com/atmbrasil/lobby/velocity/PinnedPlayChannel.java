package br.com.atmbrasil.lobby.velocity;

import java.util.Objects;

/** A reviewed PLAY channel, used only when advertised or as a bounded whole-query fallback. */
record PinnedPlayChannel(String id, String version) {
    PinnedPlayChannel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
    }
}
