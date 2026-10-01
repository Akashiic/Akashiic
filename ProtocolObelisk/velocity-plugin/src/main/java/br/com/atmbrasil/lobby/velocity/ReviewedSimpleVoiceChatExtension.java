package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bytecode-reviewed NeoForge PLAY contract added by Simple Voice Chat 1.21.1-2.6.22.
 *
 * <p>This is deliberately an atomic extension rather than a namespace wildcard. Every identifier,
 * direction, version and optional flag must match the audited artifact. The channels are advertised
 * to the client but remain externally owned: ProtocolObelisk must never register or consume them,
 * because the official Bukkit/Velocity/NeoForge components own their forwarding and secret
 * negotiation.</p>
 */
final class ReviewedSimpleVoiceChatExtension {
    static final String ARTIFACT_VERSION = "1.21.1-2.6.22";
    static final String ARTIFACT_SHA256 =
            "63116a4d21bd57221482d971dd85822f7c723c210b60411670cdb0aa26873cac";
    static final String NETWORK_VERSION = "voicechat";
    static final int COMPATIBILITY_VERSION = 20;
    static final int CANONICAL_WIRE_BYTES = 483;

    private static final List<Channel> CHANNELS = List.of(
            toServer("voicechat:update_state"),
            toClient("voicechat:state"),
            toClient("voicechat:states"),
            toClient("voicechat:remove_state"),
            toClient("voicechat:secret"),
            toServer("voicechat:request_secret"),
            toClient("voicechat:add_group"),
            toClient("voicechat:remove_group"),
            toServer("voicechat:set_group"),
            toServer("voicechat:create_group"),
            toServer("voicechat:leave_group"),
            toClient("voicechat:joined_group"),
            toClient("voicechat:add_category"),
            toClient("voicechat:remove_category"));
    private static final Map<String, Channel> CHANNELS_BY_ID = Map.copyOf(CHANNELS.stream()
            .collect(Collectors.toMap(
                    Channel::id,
                    channel -> channel,
                    (left, right) -> {
                        throw new IllegalArgumentException("duplicate reviewed voice channel");
                    },
                    LinkedHashMap::new)));
    private static final Set<String> CHANNEL_IDS = Set.copyOf(CHANNELS_BY_ID.keySet());
    private static final Set<String> EXTERNALLY_OWNED_CHANNEL_IDS = Set.of(
            "voicechat:update_state",
            "voicechat:state",
            "voicechat:states",
            "voicechat:remove_state",
            "voicechat:secret",
            "voicechat:request_secret",
            "voicechat:add_group",
            "voicechat:remove_group",
            "voicechat:set_group",
            "voicechat:create_group",
            "voicechat:leave_group",
            "voicechat:joined_group",
            "voicechat:add_category",
            "voicechat:remove_category",
            "vc:secret",
            "vc:request_secret");

    private ReviewedSimpleVoiceChatExtension() {
    }

    static List<Channel> channels() {
        return CHANNELS;
    }

    static Set<String> channelIds() {
        return CHANNEL_IDS;
    }

    /** The 14 NeoForge contract ids plus both legacy aliases registered by Velocity. */
    static Set<String> externallyOwnedChannelIds() {
        return EXTERNALLY_OWNED_CHANNEL_IDS;
    }

    /** Exact process-wide ownership fence for the official Simple Voice Chat components. */
    static boolean externallyOwns(String channelId) {
        return EXTERNALLY_OWNED_CHANNEL_IDS.contains(
                Objects.requireNonNull(channelId, "channelId"));
    }

    static void requireProtocolObeliskSinkOwnership(String channelId)
            throws ProtocolViolationException {
        if (externallyOwns(channelId)) {
            throw new ProtocolViolationException(
                    "PLAY sink is externally owned by Simple Voice Chat: " + channelId);
        }
    }

    /** Returns true only when the complete, exact extension is present and PLAY-only. */
    static boolean matches(Registry registry) {
        Objects.requireNonNull(registry, "registry");
        List<Channel> observedPlay = registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL)
                .stream()
                .filter(channel -> namespace(channel.id()).equals("voicechat"))
                .toList();
        if (observedPlay.size() != CHANNELS.size()) {
            return false;
        }
        Map<String, Channel> observedById = observedPlay.stream()
                .collect(Collectors.toMap(Channel::id, channel -> channel));
        if (!CHANNELS_BY_ID.equals(observedById)) {
            return false;
        }
        return registry.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL).stream()
                .noneMatch(channel -> namespace(channel.id()).equals("voicechat"));
    }

    static boolean absent(Registry registry) {
        Objects.requireNonNull(registry, "registry");
        return registry.protocols().values().stream()
                .flatMap(List::stream)
                .noneMatch(channel -> namespace(channel.id()).equals("voicechat"));
    }

    private static Channel toServer(String id) {
        return new Channel(id, NETWORK_VERSION, Flow.SERVERBOUND, true);
    }

    private static Channel toClient(String id) {
        return new Channel(id, NETWORK_VERSION, Flow.CLIENTBOUND, true);
    }

    private static String namespace(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(0, separator);
    }
}
