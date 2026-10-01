package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.util.Objects;
import java.util.Set;

/**
 * Ownership fence for Velocity's native plugin-message transport.
 *
 * <p>Preserving a registration for Paper does not give ProtocolObelisk ownership of its payloads.
 * A client advertisement or administrator pin cannot turn this transport into a no-op PLAY sink.
 * This class grants no routing authority: Velocity retains its own source validation and handling.
 * The modern identifier is the only identifier advertised into modern channel contracts, while
 * the legacy Bukkit alias is recognized defensively at event-dispatch time.</p>
 */
final class ProxyPluginMessageOwnership {
    static final String BUNGEE_CHANNEL_ID = "bungeecord:main";
    static final String LEGACY_BUNGEE_CHANNEL_ID = "BungeeCord";
    private static final Set<String> CHANNEL_IDS = Set.of(BUNGEE_CHANNEL_ID);

    private ProxyPluginMessageOwnership() {
    }

    static Set<String> channelIds() {
        return CHANNEL_IDS;
    }

    static boolean externallyOwns(String channelId) {
        String id = Objects.requireNonNull(channelId, "channelId");
        return CHANNEL_IDS.contains(id) || LEGACY_BUNGEE_CHANNEL_ID.equalsIgnoreCase(id);
    }

    static void requireProtocolObeliskSinkOwnership(String channelId)
            throws ProtocolViolationException {
        if (externallyOwns(channelId)) {
            throw new ProtocolViolationException(
                    "PLAY sink is externally owned by the proxy transport: " + channelId);
        }
    }
}
