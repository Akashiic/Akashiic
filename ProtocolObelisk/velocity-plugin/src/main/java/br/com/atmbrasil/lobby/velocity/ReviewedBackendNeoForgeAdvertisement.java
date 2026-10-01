package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact canonical client advertisements allowed to cross an otherwise strict ignored-channel
 * boundary.
 *
 * <p>The normal relay path accepts only a fully decoded advertisement with zero ignored channels.
 * ATM10 Normal 8.0 is a reviewed exception because its genuine NeoForge advertisement contains
 * one syntactically unusable {@code ae2:} identifier. The reviewed client has emitted the same
 * canonical registry with a different raw fingerprint across launches, so raw SHA-256 is
 * diagnostic rather than an identity field. This catalog instead binds the exception to the
 * complete canonical contract, exact profile and exact ignored-component semantics; an
 * ignored-channel count alone is never sufficient.</p>
 */
final class ReviewedBackendNeoForgeAdvertisement {
    private static final Evidence ATM10_NORMAL_8_0 = evidence(
            "atm10-normal-8.0-canonical-neoforge-advertisement",
            ReviewedClientContractEvidence.ATM10_NORMAL_8_0,
            "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247");
    private static final Evidence ATM10_NORMAL_8_0_WITHOUT_WORLD_EDIT_CUI = evidence(
            "atm10-normal-8.0-without-worldedit-cui-neoforge-advertisement",
            ReviewedClientContractEvidence.ATM10_NORMAL_8_0_WITHOUT_WORLD_EDIT_CUI,
            "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247");
    private static final Evidence ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT = evidence(
            "atm10-normal-8.0-simple-voice-chat-neoforge-advertisement",
            ReviewedClientContractEvidence.ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT,
            "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247");
    private static final Evidence ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT_WITHOUT_WORLD_EDIT_CUI =
            evidence(
                    "atm10-normal-8.0-simple-voice-chat-without-worldedit-cui-neoforge-"
                            + "advertisement",
                    ReviewedClientContractEvidence
                            .ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT_WITHOUT_WORLD_EDIT_CUI,
                    "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247");
    private static final List<Evidence> REVIEWED = List.of(
            ATM10_NORMAL_8_0,
            ATM10_NORMAL_8_0_WITHOUT_WORLD_EDIT_CUI,
            ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT,
            ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT_WITHOUT_WORLD_EDIT_CUI);

    private ReviewedBackendNeoForgeAdvertisement() {
    }

    private static Evidence evidence(
            String id,
            ReviewedClientContractEvidence.Evidence observedContract,
            String profileId) {
        ReviewedClientContractEvidence.Atm10Normal80Variant variant =
                ReviewedClientContractEvidence.atm10Normal80Variants().stream()
                        .filter(candidate -> candidate.observedContract().equals(observedContract))
                        .findFirst()
                        .orElseThrow();
        return new Evidence(
                id,
                variant,
                profileId,
                variant.rawBytes(),
                variant.declaredChannels(),
                variant.usableChannels(),
                List.of(new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "ae2:",
                        "ae2",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)));
    }

    static Optional<Evidence> match(
            int minecraftProtocol,
            int rawAdvertisementBytes,
            Registry decodedRegistry,
            ChannelContractSignature.Signatures signatures,
            SilentGearEmbeddedProfile selectedProfile) {
        Objects.requireNonNull(decodedRegistry, "decodedRegistry");
        Objects.requireNonNull(signatures, "signatures");
        if (selectedProfile == null) {
            return Optional.empty();
        }
        return REVIEWED.stream()
                .filter(evidence -> evidence.matches(
                        minecraftProtocol,
                        rawAdvertisementBytes,
                        decodedRegistry,
                        signatures,
                        selectedProfile))
                .findFirst();
    }

    record Evidence(
            String id,
            ReviewedClientContractEvidence.Atm10Normal80Variant clientVariant,
            String profileId,
            int rawBytes,
            int declaredChannels,
            int usableChannels,
            List<IgnoredChannel> ignoredChannels) {
        Evidence {
            if (Objects.requireNonNull(id, "id").isBlank()) {
                throw new IllegalArgumentException("advertisement evidence id must not be blank");
            }
            Objects.requireNonNull(clientVariant, "clientVariant");
            if (Objects.requireNonNull(profileId, "profileId").isBlank()) {
                throw new IllegalArgumentException("profileId must not be blank");
            }
            if (rawBytes <= 0) {
                throw new IllegalArgumentException("rawBytes must be positive");
            }
            ignoredChannels = List.copyOf(Objects.requireNonNull(
                    ignoredChannels, "ignoredChannels"));
            if (declaredChannels <= 0
                    || usableChannels <= 0
                    || ignoredChannels.isEmpty()
                    || declaredChannels - ignoredChannels.size() != usableChannels
                    || rawBytes != clientVariant.rawBytes()
                    || declaredChannels != clientVariant.declaredChannels()
                    || usableChannels != clientVariant.usableChannels()) {
                throw new IllegalArgumentException("inconsistent advertisement channel counts");
            }
        }

        private boolean matches(
                int observedMinecraftProtocol,
                int observedRawBytes,
                Registry observedRegistry,
                ChannelContractSignature.Signatures observedSignatures,
                SilentGearEmbeddedProfile observedProfile) {
            return rawBytes == observedRawBytes
                    && declaredChannels == observedRegistry.declaredChannelCount()
                    && ignoredChannels.size() == observedRegistry.ignoredChannelCount()
                    && ignoredChannels.equals(observedRegistry.ignoredChannels())
                    && usableChannels == observedRegistry.channelCount()
                    && clientVariant.matches(
                            observedMinecraftProtocol, observedRegistry, observedSignatures)
                    && observedProfile.minecraftProtocol() == observedMinecraftProtocol
                    && profileId.equals(observedProfile.profileId())
                    && observedProfile.fullClientContractSha256()
                            .filter(clientVariant.normalizedFullClientContractSha256()::equals)
                            .isPresent()
                    && observedProfile.silentGearChannelContractSha256().equals(
                            observedSignatures.silentGearContractSha256());
        }
    }
}
