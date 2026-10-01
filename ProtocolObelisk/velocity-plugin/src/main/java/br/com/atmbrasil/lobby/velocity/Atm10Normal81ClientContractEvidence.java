package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact structural evidence for ATM10 8.1 with reviewed optional network extensions.
 *
 * <p>The captured canonical contract includes WorldEdit CUI and Simple Voice Chat. For identity
 * comparison only, an entirely absent extension may be added to a private copy of the advertisement.
 * Every remaining channel must still produce the complete canonical SHA-256; there is no observed
 * hash allowlist, namespace fallback, modlist gate, or approximate matching. The comparison copy
 * must never be advertised or sent to the client. Admission and routing do not consume this result.</p>
 */
final class Atm10Normal81ClientContractEvidence {
    private static final Channel WORLD_EDIT_CUI =
            new Channel("worldedit:cui", "1", Flow.BIDIRECTIONAL, true);

    private Atm10Normal81ClientContractEvidence() {
    }

    static Optional<Match> match(
            int minecraftProtocol,
            Registry registry,
            ChannelContractSignature.Signatures signatures) {
        return matchExactContract(
                minecraftProtocol,
                registry,
                signatures,
                Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                SilentGearProtocol.ATM10_NORMAL_4_2.canonicalContractSha256());
    }

    /**
     * Package-private deterministic test seam. Only {@link #match} supplies production identities;
     * callers must not derive an expected identity from the untrusted advertisement being matched.
     */
    static Optional<Match> matchExactContract(
            int minecraftProtocol,
            Registry registry,
            ChannelContractSignature.Signatures signatures,
            String expectedFullContractSha256,
            String expectedSilentGearContractSha256) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(signatures, "signatures");
        requireSha256(expectedFullContractSha256);
        requireSha256(expectedSilentGearContractSha256);
        if (minecraftProtocol != Atm10Normal81Contract.MINECRAFT_PROTOCOL
                || registry.protocols().keySet().stream().anyMatch(protocol ->
                        protocol != NeoForgeHandshakeCodec.PLAY_PROTOCOL
                                && protocol != NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL)) {
            return Optional.empty();
        }
        // A supplied digest is not authority: bind it back to the actual decoded advertisement.
        ChannelContractSignature.Signatures observed = ChannelContractSignature.from(registry);
        if (!observed.equals(signatures)
                || !expectedSilentGearContractSha256.equals(observed.silentGearContractSha256())) {
            return Optional.empty();
        }
        // Full signatures exclude ignored entries. A skipped duplicate can conceal a changed
        // structural channel, and a malformed optional component is not an absent extension.
        if (registry.ignoredChannels().stream().anyMatch(channel ->
                channel.reason() == IgnoredChannelReason.DUPLICATE_CHANNEL_ID
                        || isVoiceChat(channel.id())
                        || channel.id().equals(WORLD_EDIT_CUI.id()))) {
            return Optional.empty();
        }

        List<Channel> play = registry.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL);
        List<Channel> configuration =
                registry.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL);
        List<Channel> observedCui = play.stream()
                .filter(channel -> channel.id().equals(WORLD_EDIT_CUI.id()))
                .toList();
        if (configuration.stream().anyMatch(channel -> channel.id().equals(WORLD_EDIT_CUI.id()))
                || (!observedCui.isEmpty() && !observedCui.equals(List.of(WORLD_EDIT_CUI)))) {
            return Optional.empty();
        }
        boolean cuiPresent = !observedCui.isEmpty();

        List<Channel> observedVoice = play.stream()
                .filter(channel -> isVoiceChat(channel.id()))
                .toList();
        boolean voicePresent = !observedVoice.isEmpty();
        if (configuration.stream().anyMatch(channel -> isVoiceChat(channel.id()))
                || (voicePresent
                        && (observedVoice.size() != ReviewedSimpleVoiceChatExtension.channels().size()
                                || !new HashSet<>(observedVoice).equals(
                                        new HashSet<>(ReviewedSimpleVoiceChatExtension.channels()))))) {
            return Optional.empty();
        }

        List<Channel> comparisonPlay = new ArrayList<>(play);
        if (!cuiPresent) {
            comparisonPlay.add(WORLD_EDIT_CUI);
        }
        if (!voicePresent) {
            comparisonPlay.addAll(ReviewedSimpleVoiceChatExtension.channels());
        }
        Map<Integer, List<Channel>> comparisonProtocols = new LinkedHashMap<>(registry.protocols());
        comparisonProtocols.put(NeoForgeHandshakeCodec.PLAY_PROTOCOL, List.copyOf(comparisonPlay));
        Registry comparison = new Registry(
                comparisonProtocols,
                registry.declaredChannelCount() + comparisonPlay.size() - play.size(),
                registry.ignoredChannelCount(),
                registry.ignoredChannels());
        if (!expectedFullContractSha256.equals(
                ChannelContractSignature.from(comparison).fullContractSha256())) {
            return Optional.empty();
        }
        String id = Atm10Normal81Contract.ID
                + (cuiPresent ? "-worldedit-cui" : "-without-worldedit-cui")
                + (voicePresent ? "-simple-voice-chat" : "-without-simple-voice-chat");
        return Optional.of(new Match(
                id,
                expectedFullContractSha256,
                cuiPresent,
                voicePresent,
                voicePresent ? observedVoice : List.of()));
    }

    private static boolean isVoiceChat(String channelId) {
        return channelId.equals("voicechat") || channelId.startsWith("voicechat:");
    }

    private static void requireSha256(String value) {
        if (!Objects.requireNonNull(value, "expected contract SHA-256").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expected contract must be a lowercase SHA-256");
        }
    }

    record Match(
            String id,
            String normalizedFullClientContractSha256,
            boolean worldEditCuiPresent,
            boolean simpleVoiceChatPresent,
            List<Channel> externallyOwnedPlayChannels) {
        Match {
            Objects.requireNonNull(id, "id");
            requireSha256(normalizedFullClientContractSha256);
            externallyOwnedPlayChannels = List.copyOf(externallyOwnedPlayChannels);
        }
    }
}
