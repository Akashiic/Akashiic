package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Canonical client contracts used as identity evidence for reviewed embedded profiles. */
final class ReviewedClientContractEvidence {
    static final Evidence ATM10_NORMAL_7_3 = new Evidence(
            "atm10-normal-7.3-canonical-contract",
            767,
            "cfce57a5a93240f97d570e952c3b4d71e5fac1f11504289f5ea4265371da559a",
            SilentGearProtocol.ATM10_NORMAL_4_2);
    static final Evidence ATM10_NORMAL_8_0 = new Evidence(
            "atm10-normal-8.0-canonical-contract",
            767,
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f",
            SilentGearProtocol.ATM10_NORMAL_4_2);

    /** Exact base fixture without only the optional {@code worldedit:cui@1} component. */
    static final Evidence ATM10_NORMAL_8_0_WITHOUT_WORLD_EDIT_CUI = new Evidence(
            "atm10-normal-8.0-without-optional-worldedit-cui",
            767,
            "0b4b9a75c0a92214590e30ad5bb1a09e6d9ca93bda06ac9227bec02c4cef89f2",
            SilentGearProtocol.ATM10_NORMAL_4_2);

    /** Captured 2026-08-24 and reproduced from the base fixture plus the audited 14 channels. */
    static final Evidence ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT = new Evidence(
            "atm10-normal-8.0-simple-voice-chat-2.6.x",
            767,
            "a6e0f7cca6d781f86b357f8e8b50e5d56a4906264b6fc6377f2ff84c87491646",
            SilentGearProtocol.ATM10_NORMAL_4_2);

    /** Second live capture: the same voice extension without optional WorldEdit CUI. */
    static final Evidence ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT_WITHOUT_WORLD_EDIT_CUI =
            new Evidence(
                    "atm10-normal-8.0-simple-voice-chat-2.6.x-without-worldedit-cui",
                    767,
                    "37d53b0f1dddc49a279e1ca419c60e9cecf56ff1f4080c8a9f88a40e80871017",
                    SilentGearProtocol.ATM10_NORMAL_4_2);

    private static final Channel WORLD_EDIT_CUI =
            new Channel("worldedit:cui", "1", Flow.BIDIRECTIONAL, true);
    private static final IgnoredChannel INVALID_AE2_COMPONENT = new IgnoredChannel(
            NeoForgeHandshakeCodec.PLAY_PROTOCOL,
            "ae2:",
            "ae2",
            Flow.CLIENTBOUND,
            false,
            IgnoredChannelReason.INVALID_RESOURCE_LOCATION);

    private static final List<Atm10Normal80Variant> ATM10_NORMAL_8_0_VARIANTS = List.of(
            new Atm10Normal80Variant(
                    ATM10_NORMAL_8_0, true, false, 2_404, 2_403, 103_657),
            new Atm10Normal80Variant(
                    ATM10_NORMAL_8_0_WITHOUT_WORLD_EDIT_CUI,
                    false, false, 2_403, 2_402, 103_639),
            new Atm10Normal80Variant(
                    ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT,
                    true, true, 2_418, 2_417, 104_140),
            new Atm10Normal80Variant(
                    ATM10_NORMAL_8_0_SIMPLE_VOICE_CHAT_WITHOUT_WORLD_EDIT_CUI,
                    false, true, 2_417, 2_416, 104_122));

    private ReviewedClientContractEvidence() {
    }

    /**
     * Resolves a complete Normal 8.0 identity and validates its structure in addition to its
     * canonical SHA-256. No namespace or partial-channel fallback exists.
     */
    static Optional<Atm10Normal80Variant> matchAtm10Normal80(
            int minecraftProtocol,
            Registry registry,
            ChannelContractSignature.Signatures signatures) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(signatures, "signatures");
        return ATM10_NORMAL_8_0_VARIANTS.stream()
                .filter(variant -> variant.matches(minecraftProtocol, registry, signatures))
                .findFirst();
    }

    /** Exact Normal 7.3 identity including the one reviewed ignored AE2 component. */
    static boolean matchesAtm10Normal73(
            int minecraftProtocol,
            Registry registry,
            ChannelContractSignature.Signatures signatures) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(signatures, "signatures");
        return ATM10_NORMAL_7_3.matches(minecraftProtocol, signatures)
                && registry.declaredChannelCount() == 2_381
                && registry.channelCount() == 2_380
                && registry.ignoredChannelCount() == 1
                && registry.ignoredChannels().equals(List.of(INVALID_AE2_COMPONENT));
    }

    /** Only complete, captured Normal contracts may allocate process-global automatic sinks. */
    static boolean allowsAutomaticPlaySinks(
            int minecraftProtocol,
            Registry registry,
            ChannelContractSignature.Signatures signatures) {
        return matchesAtm10Normal73(minecraftProtocol, registry, signatures)
                || matchAtm10Normal80(minecraftProtocol, registry, signatures).isPresent();
    }

    /** Hash-level identity for code paths which already consumed a structurally validated query. */
    static boolean matchesAtm10Normal80(
            int minecraftProtocol,
            ChannelContractSignature.Signatures signatures) {
        Objects.requireNonNull(signatures, "signatures");
        return ATM10_NORMAL_8_0_VARIANTS.stream()
                .anyMatch(variant -> variant.observedContract().matches(
                        minecraftProtocol, signatures));
    }

    static boolean isAtm10Normal80ObservedHash(
            ChannelContractSignature.Signatures signatures) {
        Objects.requireNonNull(signatures, "signatures");
        return ATM10_NORMAL_8_0_VARIANTS.stream()
                .anyMatch(variant -> variant.observedContract().fullClientContractSha256()
                        .equals(signatures.fullContractSha256()));
    }

    static boolean isAtm10Normal73ObservedHash(
            ChannelContractSignature.Signatures signatures) {
        Objects.requireNonNull(signatures, "signatures");
        return ATM10_NORMAL_7_3.fullClientContractSha256()
                .equals(signatures.fullContractSha256());
    }

    static List<Atm10Normal80Variant> atm10Normal80Variants() {
        return ATM10_NORMAL_8_0_VARIANTS;
    }

    record Evidence(
            String id,
            int minecraftProtocol,
            String fullClientContractSha256,
            SilentGearProtocol.Contract silentGearContract) {
        Evidence {
            if (Objects.requireNonNull(id, "id").isBlank()) {
                throw new IllegalArgumentException("evidence id must not be blank");
            }
            if (minecraftProtocol <= 0) {
                throw new IllegalArgumentException("minecraftProtocol must be positive");
            }
            requireSha256(fullClientContractSha256);
            Objects.requireNonNull(silentGearContract, "silentGearContract");
        }

        boolean matches(
                int observedMinecraftProtocol,
                ChannelContractSignature.Signatures signatures) {
            Objects.requireNonNull(signatures, "signatures");
            return minecraftProtocol == observedMinecraftProtocol
                    && fullClientContractSha256.equals(signatures.fullContractSha256())
                    && silentGearContract.canonicalContractSha256().equals(
                            signatures.silentGearContractSha256());
        }

        private static void requireSha256(String value) {
            Objects.requireNonNull(value, "value");
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "client contract evidence requires lowercase SHA-256");
            }
        }
    }

    /** One exact wire-shape variant which normalizes to the same immutable Normal 8.0 profile. */
    record Atm10Normal80Variant(
            Evidence observedContract,
            boolean worldEditCuiPresent,
            boolean simpleVoiceChatPresent,
            int declaredChannels,
            int usableChannels,
            int rawBytes) {
        Atm10Normal80Variant {
            Objects.requireNonNull(observedContract, "observedContract");
            if (declaredChannels <= 0
                    || usableChannels <= 0
                    || declaredChannels - usableChannels != 1
                    || rawBytes <= 0) {
                throw new IllegalArgumentException("invalid ATM10 Normal 8.0 variant shape");
            }
        }

        String id() {
            return observedContract.id();
        }

        String normalizedFullClientContractSha256() {
            return ATM10_NORMAL_8_0.fullClientContractSha256();
        }

        List<Channel> externallyOwnedPlayChannels() {
            return simpleVoiceChatPresent
                    ? ReviewedSimpleVoiceChatExtension.channels()
                    : List.of();
        }

        boolean matches(
                int minecraftProtocol,
                Registry registry,
                ChannelContractSignature.Signatures signatures) {
            if (!observedContract.matches(minecraftProtocol, signatures)
                    || registry.declaredChannelCount() != declaredChannels
                    || registry.channelCount() != usableChannels
                    || registry.ignoredChannelCount() != 1
                    || !registry.ignoredChannels().equals(List.of(INVALID_AE2_COMPONENT))) {
                return false;
            }
            boolean hasExactWorldEdit = registry
                    .channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL)
                    .contains(WORLD_EDIT_CUI);
            boolean hasAnyWorldEditCui = registry
                    .channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                    .anyMatch(channel -> channel.id().equals(WORLD_EDIT_CUI.id()));
            if (worldEditCuiPresent != hasExactWorldEdit
                    || (!worldEditCuiPresent && hasAnyWorldEditCui)) {
                return false;
            }
            return simpleVoiceChatPresent
                    ? ReviewedSimpleVoiceChatExtension.matches(registry)
                    : ReviewedSimpleVoiceChatExtension.absent(registry);
        }
    }
}
