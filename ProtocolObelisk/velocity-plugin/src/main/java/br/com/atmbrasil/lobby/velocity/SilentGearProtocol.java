package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact, reviewed channel contracts for supported Silent Gear network generations. */
final class SilentGearProtocol {
    static final String SYNC_TRAITS = "silentgear:sync_traits";
    static final String SYNC_MATERIALS = "silentgear:sync_materials";
    static final String SYNC_PARTS = "silentgear:sync_parts";
    static final String ACK = "silentgear:ack";
    static final String COMMAND_OUTPUT = "silentgear:command_output";
    static final String OPEN_GUIDE_BOOK = "silentgear:open_guide_book";
    static final String ALLOY_MAKER_UPDATE = "silentgear:alloy_maker_update";
    static final String TOGGLE_WORK_MODE = "silentgear:toggle_work_mode";
    static final String SWING_GEAR = "silentgear:swing_gear";
    static final String KEY_PRESS_ON_ITEM = "silentgear:key_press_on_item";
    static final String RECALCULATE_STATS = "silentgear:recalculate_stats";
    static final String SELECT_BLUEPRINT_IN_BOOK = "silentgear:select_blueprint_in_book";
    static final List<String> SYNC_CHANNELS = List.of(
            SYNC_TRAITS,
            SYNC_MATERIALS,
            SYNC_PARTS);

    static final Contract ATM10_TTS_4_1_3 = contract(
            "atm10-tts-silentgear-4.1.3",
            "1.21.1-4.1.3.1",
            "4.1.3",
            List.of(
                    required(SYNC_TRAITS, "4.1.3", Flow.CLIENTBOUND),
                    required(SYNC_MATERIALS, "4.1.3", Flow.CLIENTBOUND),
                    required(SYNC_PARTS, "4.1.3", Flow.CLIENTBOUND),
                    required(ACK, "4.1.3", Flow.SERVERBOUND),
                    required(COMMAND_OUTPUT, "4.1.3", Flow.CLIENTBOUND),
                    required(OPEN_GUIDE_BOOK, "4.1.3", Flow.CLIENTBOUND),
                    required(ALLOY_MAKER_UPDATE, "4.1.3", Flow.SERVERBOUND),
                    required(SWING_GEAR, "4.1.3", Flow.SERVERBOUND),
                    required(KEY_PRESS_ON_ITEM, "4.1.3", Flow.SERVERBOUND),
                    required(RECALCULATE_STATS, "4.1.3", Flow.SERVERBOUND),
                    required(SELECT_BLUEPRINT_IN_BOOK, "4.1.3", Flow.SERVERBOUND)));

    /** Exact Silent Gear 4.2 channel contract used by the canonical ATM10 Normal profile. */
    static final Contract ATM10_NORMAL_4_2 = contract(
            "atm10-normal-silentgear-4.2",
            "1.21.1-4.2.1.1",
            "4.2",
            List.of(
                    required(SYNC_TRAITS, "4.2", Flow.CLIENTBOUND),
                    required(SYNC_MATERIALS, "4.2", Flow.CLIENTBOUND),
                    required(SYNC_PARTS, "4.2", Flow.CLIENTBOUND),
                    required(ACK, "4.2", Flow.SERVERBOUND),
                    required(COMMAND_OUTPUT, "4.2", Flow.CLIENTBOUND),
                    required(OPEN_GUIDE_BOOK, "4.2", Flow.CLIENTBOUND),
                    required(SWING_GEAR, "4.2", Flow.SERVERBOUND),
                    required(KEY_PRESS_ON_ITEM, "4.2", Flow.SERVERBOUND),
                    required(RECALCULATE_STATS, "4.2", Flow.SERVERBOUND),
                    required(SELECT_BLUEPRINT_IN_BOOK, "4.2", Flow.SERVERBOUND),
                    required(TOGGLE_WORK_MODE, "4.2", Flow.SERVERBOUND)));

    private static final List<Contract> REVIEWED_CHANNEL_CONTRACTS = List.of(
            ATM10_TTS_4_1_3,
            ATM10_NORMAL_4_2);

    // Backwards-compatible aliases for the immutable TTS v2 profile loader and snapshot store.
    static final String REVIEWED_MOD_VERSION = ATM10_TTS_4_1_3.modVersion();
    static final String NETWORK_VERSION = ATM10_TTS_4_1_3.networkVersion();
    private static final String LEGACY_TTS_PROFILE_CONTRACT_SHA256 = legacyContractSha256(
            ATM10_TTS_4_1_3.channels());

    private SilentGearProtocol() {
    }

    static Compatibility inspect(List<Channel> playChannels) {
        Objects.requireNonNull(playChannels, "playChannels");
        List<Channel> silentGearChannels = playChannels.stream()
                .filter(channel -> channel.id().startsWith("silentgear:"))
                .toList();
        Map<String, Channel> byId = new LinkedHashMap<>();
        boolean duplicate = false;
        for (Channel channel : silentGearChannels) {
            if (byId.putIfAbsent(channel.id(), channel) != null) {
                duplicate = true;
            }
        }

        Optional<Contract> matched = duplicate
                ? Optional.empty()
                : REVIEWED_CHANNEL_CONTRACTS.stream()
                        .filter(contract -> contract.matches(byId, silentGearChannels.size()))
                        .findFirst();
        String rejectionReason = "";
        if (duplicate) {
            rejectionReason = "duplicate silentgear channel id";
        } else if (matched.isEmpty()) {
            rejectionReason = "Silent Gear channel contract has no exact reviewed match";
        }

        List<Channel> clientbound = SYNC_CHANNELS.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        Channel ack = byId.get(ACK);
        return new Compatibility(
                matched.isPresent(),
                matched,
                clientbound,
                ack,
                matched.map(Contract::canonicalContractSha256).orElse(""),
                rejectionReason);
    }

    static boolean isSyncChannel(String channelId) {
        return SYNC_CHANNELS.contains(channelId);
    }

    static String fingerprint(byte[] queryPayload) {
        Objects.requireNonNull(queryPayload, "queryPayload");
        return sha256(queryPayload);
    }

    static String sha256(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /** Immutable TTS 4.1.3 channel list retained for source and binary regression tests. */
    static List<Channel> reviewedPlayContract() {
        return ATM10_TTS_4_1_3.channels();
    }

    /** Legacy v2 TTS profile hash retained byte-for-byte for its existing manifest. */
    static String reviewedContractSha256() {
        return LEGACY_TTS_PROFILE_CONTRACT_SHA256;
    }

    static List<Contract> reviewedChannelContracts() {
        return REVIEWED_CHANNEL_CONTRACTS;
    }

    /** Checks the map prefix without attempting to decode registry-dependent values. */
    static int validateNonEmptyMapPayload(byte[] payload, int maximumBytes) {
        Objects.requireNonNull(payload, "payload");
        if (maximumBytes < 1 || payload.length < 1 || payload.length > maximumBytes) {
            throw new IllegalArgumentException("Silent Gear payload size is outside bounds");
        }
        int value = 0;
        for (int index = 0; index < Math.min(5, payload.length); index++) {
            int current = payload[index] & 0xFF;
            value |= (current & 0x7F) << (index * 7);
            if ((current & 0x80) == 0) {
                if (value <= 0) {
                    throw new IllegalArgumentException("Silent Gear payload map is empty");
                }
                return value;
            }
        }
        throw new IllegalArgumentException("Silent Gear payload has an overlong map size");
    }

    private static Contract contract(
            String id,
            String modVersion,
            String networkVersion,
            List<Channel> channels) {
        return new Contract(
                id,
                modVersion,
                networkVersion,
                channels,
                ChannelContractSignature.silentGearPlayContract(channels));
    }

    private static Channel required(String id, String version, Flow flow) {
        return new Channel(id, version, flow, false);
    }

    /** Historical unhashed framing used by the already published TTS v2 manifest. */
    private static String legacyContractSha256(List<Channel> channels) {
        String canonical = channels.stream()
                .map(channel -> channel.id()
                        + '@' + channel.version()
                        + '|' + channel.flow().name()
                        + '|' + channel.optional())
                .collect(java.util.stream.Collectors.joining("\n"));
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    record Contract(
            String id,
            String modVersion,
            String networkVersion,
            List<Channel> channels,
            String canonicalContractSha256) {
        Contract {
            if (Objects.requireNonNull(id, "id").isBlank()
                    || Objects.requireNonNull(modVersion, "modVersion").isBlank()
                    || Objects.requireNonNull(networkVersion, "networkVersion").isBlank()) {
                throw new IllegalArgumentException("Silent Gear contract metadata must not be blank");
            }
            channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
            if (channels.isEmpty()
                    || channels.stream().map(Channel::id).distinct().count() != channels.size()) {
                throw new IllegalArgumentException(
                        "Silent Gear contract channels must be non-empty and unique");
            }
            Objects.requireNonNull(canonicalContractSha256, "canonicalContractSha256");
        }

        private boolean matches(Map<String, Channel> byId, int observedSize) {
            if (observedSize != channels.size()
                    || !byId.keySet().equals(channels.stream()
                            .map(Channel::id)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()))) {
                return false;
            }
            return channels.stream().allMatch(expected -> expected.equals(byId.get(expected.id())));
        }
    }

    record Compatibility(
            boolean exact,
            Optional<Contract> contract,
            List<Channel> clientboundChannels,
            Channel ackChannel,
            String contractSha256,
            String rejectionReason) {
        Compatibility {
            contract = Objects.requireNonNull(contract, "contract");
            clientboundChannels = List.copyOf(Objects.requireNonNull(
                    clientboundChannels, "clientboundChannels"));
            Objects.requireNonNull(contractSha256, "contractSha256");
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            if (exact != contract.isPresent()) {
                throw new IllegalArgumentException("exact contract flag is inconsistent");
            }
        }

        String networkVersion() {
            return contract.map(Contract::networkVersion).orElse("unrecognized");
        }

        String contractId() {
            return contract.map(Contract::id).orElse("unrecognized");
        }
    }
}
