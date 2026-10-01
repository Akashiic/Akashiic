package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Stable, order-independent signatures for a decoded NeoForge channel advertisement.
 *
 * <p>The encoding is deliberately length-framed. It is shared with the passive profile-capture
 * companion, so an exact client contract can be selected without relying on the order-sensitive
 * SHA-256 of the raw query packet.</p>
 */
final class ChannelContractSignature {
    private static final Comparator<ProtocolChannel> CANONICAL_ORDER = Comparator
            .comparingInt(ProtocolChannel::protocol)
            .thenComparing(channel -> channel.channel().id())
            .thenComparing(channel -> channel.channel().version())
            .thenComparing(channel -> channel.channel().flow().name())
            .thenComparing(channel -> channel.channel().optional());

    private ChannelContractSignature() {
    }

    static Signatures from(Registry registry) {
        Objects.requireNonNull(registry, "registry");
        List<ProtocolChannel> channels = flatten(registry);
        return new Signatures(
                sha256(channels, ignored -> true),
                sha256(channels, channel -> channel.channel().id().startsWith("silentgear:")));
    }

    static String silentGearPlayContract(List<Channel> playChannels) {
        Objects.requireNonNull(playChannels, "playChannels");
        List<ProtocolChannel> channels = playChannels.stream()
                .map(channel -> new ProtocolChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        Objects.requireNonNull(channel, "channel")))
                .toList();
        return sha256(
                channels,
                channel -> channel.channel().id().startsWith("silentgear:"));
    }

    private static List<ProtocolChannel> flatten(Registry registry) {
        List<ProtocolChannel> channels = new ArrayList<>(registry.channelCount());
        registry.protocols().forEach((protocol, protocolChannels) -> protocolChannels.forEach(
                channel -> channels.add(new ProtocolChannel(protocol, channel))));
        return List.copyOf(channels);
    }

    private static String sha256(
            List<ProtocolChannel> allChannels,
            Predicate<ProtocolChannel> filter) {
        List<ProtocolChannel> canonical = allChannels.stream()
                .filter(filter)
                .sorted(CANONICAL_ORDER)
                .toList();
        if (canonical.isEmpty()) {
            return "";
        }

        MessageDigest digest = newSha256();
        updateInt(digest, canonical.size());
        for (ProtocolChannel protocolChannel : canonical) {
            Channel channel = protocolChannel.channel();
            updateInt(digest, protocolChannel.protocol());
            updateUtf8(digest, channel.id());
            updateUtf8(digest, channel.version());
            updateInt(digest, channel.flow().wireOrdinal());
            digest.update((byte) (channel.optional() ? 1 : 0));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateUtf8(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    record Signatures(String fullContractSha256, String silentGearContractSha256) {
        Signatures {
            Objects.requireNonNull(fullContractSha256, "fullContractSha256");
            Objects.requireNonNull(silentGearContractSha256, "silentGearContractSha256");
        }
    }

    private record ProtocolChannel(int protocol, Channel channel) {
        private ProtocolChannel {
            if (protocol != NeoForgeHandshakeCodec.PLAY_PROTOCOL
                    && protocol != NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL) {
                throw new IllegalArgumentException("unsupported NeoForge protocol ordinal");
            }
            Objects.requireNonNull(channel, "channel");
        }
    }
}
