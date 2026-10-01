package br.com.atmbrasil.lobby.velocity;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Builds a bounded client-to-server PLAY sink without identifying a modpack. */
final class PlaySinkPlanner {
    private static final Comparator<Channel> AUTOMATIC_PRIORITY = Comparator
            .comparing((Channel channel) -> !looksLikeJoinTraffic(channel.id()))
            .thenComparing(Channel::optional)
            .thenComparing(channel -> !looksLikeInputTraffic(channel.id()))
            .thenComparingInt(channel -> channel.flow() == Flow.SERVERBOUND ? 0 : 1)
            .thenComparing(Channel::id)
            .thenComparing(Channel::version);

    private PlaySinkPlanner() {
    }

    static Plan plan(
            byte[] queryPayload,
            ProtocolLimits limits,
            List<PinnedPlayChannel> pinnedChannels,
            int maximumAutomaticChannels) {
        return plan(
                queryPayload,
                limits,
                pinnedChannels,
                maximumAutomaticChannels,
                true);
    }

    static Plan plan(
            byte[] queryPayload,
            ProtocolLimits limits,
            List<PinnedPlayChannel> pinnedChannels,
            int maximumAutomaticChannels,
            boolean enableApothicEnchantingBootstrap) {
        return plan(
                queryPayload,
                limits,
                pinnedChannels,
                maximumAutomaticChannels,
                enableApothicEnchantingBootstrap,
                false,
                false);
    }

    static Plan plan(
            byte[] queryPayload,
            ProtocolLimits limits,
            List<PinnedPlayChannel> pinnedChannels,
            int maximumAutomaticChannels,
            boolean enableApothicEnchantingBootstrap,
            boolean enableSilentGearProfile,
            boolean silentGearProfileAvailable) {
        Objects.requireNonNull(queryPayload, "queryPayload");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(pinnedChannels, "pinnedChannels");
        if (maximumAutomaticChannels < 0) {
            throw new IllegalArgumentException("maximumAutomaticChannels must not be negative");
        }

        try {
            Registry clientRegistry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                    queryPayload, limits);
            return plan(
                    clientRegistry,
                    pinnedChannels,
                    maximumAutomaticChannels,
                    enableApothicEnchantingBootstrap,
                    enableSilentGearProfile,
                    silentGearProfileAvailable,
                    Set.of());
        } catch (ProtocolViolationException exception) {
            return rejectedMalformedQuery(exception.getMessage());
        }
    }

    /**
     * Builds the plan from an already validated registry.
     *
     * <p>The live lobby path decodes every query exactly once before consulting its immutable
     * plan cache. Keeping this overload separate prevents a cache hit from becoming a substitute
     * for protocol validation while also avoiding the second full decode performed by older
     * releases.</p>
     */
    static Plan plan(
            Registry clientRegistry,
            List<PinnedPlayChannel> pinnedChannels,
            int maximumAutomaticChannels,
            boolean enableApothicEnchantingBootstrap,
            boolean enableSilentGearProfile,
            boolean silentGearProfileAvailable) {
        return plan(
                clientRegistry,
                pinnedChannels,
                maximumAutomaticChannels,
                enableApothicEnchantingBootstrap,
                enableSilentGearProfile,
                silentGearProfileAvailable,
                Set.of());
    }

    /**
     * Builds a lobby sink while leaving reviewed externally owned channels untouched.
     *
     * <p>Excluded identifiers remain available to the caller for the negotiated setup, but this
     * planner never reserves or consumes them through ProtocolObelisk's bounded no-op sink.</p>
     */
    static Plan plan(
            Registry clientRegistry,
            List<PinnedPlayChannel> pinnedChannels,
            int maximumAutomaticChannels,
            boolean enableApothicEnchantingBootstrap,
            boolean enableSilentGearProfile,
            boolean silentGearProfileAvailable,
            Set<String> externallyOwnedPlayChannelIds) {
        return plan(
                -1,
                clientRegistry,
                pinnedChannels,
                maximumAutomaticChannels,
                enableApothicEnchantingBootstrap,
                enableSilentGearProfile,
                silentGearProfileAvailable,
                externallyOwnedPlayChannelIds);
    }

    /**
     * Builds the live plan with the observed Minecraft protocol as an identity dimension.
     * Profile-specific pins are never authorized by a channel hash alone.
     */
    static Plan plan(
            int minecraftProtocol,
            Registry clientRegistry,
            List<PinnedPlayChannel> pinnedChannels,
            int maximumAutomaticChannels,
            boolean enableApothicEnchantingBootstrap,
            boolean enableSilentGearProfile,
            boolean silentGearProfileAvailable,
            Set<String> externallyOwnedPlayChannelIds) {
        Objects.requireNonNull(clientRegistry, "clientRegistry");
        Objects.requireNonNull(pinnedChannels, "pinnedChannels");
        LinkedHashSet<String> ownershipFence = new LinkedHashSet<>(
                ReviewedSimpleVoiceChatExtension.externallyOwnedChannelIds());
        ownershipFence.addAll(ProxyPluginMessageOwnership.channelIds());
        ownershipFence.addAll(Objects.requireNonNull(
                externallyOwnedPlayChannelIds, "externallyOwnedPlayChannelIds"));
        externallyOwnedPlayChannelIds = Set.copyOf(ownershipFence);
        if (maximumAutomaticChannels < 0) {
            throw new IllegalArgumentException("maximumAutomaticChannels must not be negative");
        }
        List<Channel> advertisedPlayChannels = clientRegistry.channelsFor(
                NeoForgeHandshakeCodec.PLAY_PROTOCOL);
        ChannelContractSignature.Signatures clientSignatures =
                ChannelContractSignature.from(clientRegistry);
        Map<String, Channel> clientPlayChannels = new LinkedHashMap<>();
        int bridgeIncompatibleChannels = 0;
        for (Channel channel : advertisedPlayChannels) {
            if (canTravelToServer(channel)) {
                if (externallyOwnedPlayChannelIds.contains(channel.id())) {
                    continue;
                }
                if (!NeoForgeHandshakeCodec.isBridgeChannelIdentifier(channel.id())) {
                    bridgeIncompatibleChannels++;
                    continue;
                }
                clientPlayChannels.put(channel.id(), channel);
            }
        }

        LinkedHashMap<String, Channel> selected = new LinkedHashMap<>();
        LinkedHashSet<String> rejectedExactPinnedContracts = new LinkedHashSet<>();
        for (PinnedPlayChannel pinned : pinnedChannels) {
            Channel clientChannel = clientPlayChannels.get(pinned.id());
            if (clientChannel != null
                    && ReviewedAtmCompatibility.matchesReviewedPinnedContract(
                            minecraftProtocol,
                            pinned,
                            clientChannel,
                            clientRegistry,
                            clientSignatures)) {
                selected.put(clientChannel.id(), clientChannel);
            } else if (clientChannel != null) {
                rejectedExactPinnedContracts.add(clientChannel.id());
            }
        }

        SilentGearProtocol.Compatibility silentGear = SilentGearProtocol.inspect(
                advertisedPlayChannels);
        NeoForgeFrozenRegistryProfile.Compatibility frozenRegistries =
                NeoForgeFrozenRegistryProfile.inspectConfigurationChannels(
                        clientRegistry.channelsFor(
                                NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL));
        boolean selectSilentGear = enableSilentGearProfile
                && silentGearProfileAvailable
                && silentGear.exact()
                && frozenRegistries.exact();
        if (selectSilentGear) {
            Channel ack = silentGear.ackChannel();
            selected.put(ack.id(), ack);
        }

        List<Channel> automaticCandidates = clientPlayChannels.values().stream()
                .filter(channel -> !selected.containsKey(channel.id()))
                .filter(channel -> !rejectedExactPinnedContracts.contains(channel.id()))
                .sorted(AUTOMATIC_PRIORITY)
                .toList();
        int automaticCount = Math.min(maximumAutomaticChannels, automaticCandidates.size());
        for (int index = 0; index < automaticCount; index++) {
            Channel channel = automaticCandidates.get(index);
            selected.put(channel.id(), channel);
        }

        List<Channel> clientboundBootstraps = new ArrayList<>();
        if (enableApothicEnchantingBootstrap) {
            advertisedPlayChannels.stream()
                        .filter(channel -> channel.id().equals(
                                ApothicEnchantingBootstrapPayload.CHANNEL_ID))
                        .filter(channel -> channel.version().equals(
                                ApothicEnchantingBootstrapPayload.CHANNEL_VERSION))
                        .filter(PlaySinkPlanner::canTravelToClient)
                        .filter(channel -> !channel.optional())
                        .filter(channel -> NeoForgeHandshakeCodec.isBridgeChannelIdentifier(
                                channel.id()))
                        .limit(1)
                        .forEach(clientboundBootstraps::add);
        }
        if (selectSilentGear) {
            clientboundBootstraps.addAll(silentGear.clientboundChannels());
        }

        return new Plan(
                List.copyOf(selected.values()),
                clientboundBootstraps,
                advertisedNamespaces(clientRegistry),
                silentGear.exact(),
                frozenRegistries.exact(),
                selectSilentGear,
                silentGear.contractSha256(),
                silentGear.exact() && !frozenRegistries.exact()
                        ? "NeoForge frozen-registry contract: "
                                + frozenRegistries.rejectionReason()
                        : silentGear.rejectionReason(),
                Mode.PARSED,
                clientPlayChannels.size(),
                clientRegistry.ignoredChannelCount()
                        + bridgeIncompatibleChannels
                        + rejectedExactPinnedContracts.size(),
                automaticCandidates.size() - automaticCount,
                "");
    }

    private static Plan rejectedMalformedQuery(String failureReason) {
        return new Plan(
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                "",
                "query registry could not be decoded",
                Mode.PINNED_FALLBACK,
                -1,
                -1,
                0,
                failureReason == null ? "unspecified decoder failure" : failureReason);
    }

    private static boolean canTravelToServer(Channel channel) {
        return channel.flow() == Flow.SERVERBOUND || channel.flow() == Flow.BIDIRECTIONAL;
    }

    private static boolean canTravelToClient(Channel channel) {
        return channel.flow() == Flow.CLIENTBOUND || channel.flow() == Flow.BIDIRECTIONAL;
    }

    private static List<String> advertisedNamespaces(Registry registry) {
        return registry.channelIds().stream()
                .map(PlaySinkPlanner::namespace)
                .distinct()
                .sorted()
                .toList();
    }

    private static String namespace(String resourceLocation) {
        int separator = resourceLocation.indexOf(':');
        return separator < 0 ? resourceLocation : resourceLocation.substring(0, separator);
    }

    private static boolean looksLikeJoinTraffic(String id) {
        int separator = id.indexOf(':');
        String path = separator < 0 ? id : id.substring(separator + 1);
        return path.contains("login")
                || path.contains("logging_in")
                || path.contains("join")
                || path.contains("hello")
                || path.contains("handshake")
                || path.contains("request_player")
                || path.contains("player_settings")
                || path.contains("sync_settings")
                || path.contains("client_settings")
                || path.contains("client_ready")
                || path.contains("client_logged")
                || path.contains("set_default")
                || path.contains("initial_sync")
                || path.contains("initial_state")
                || path.contains("bootstrap");
    }

    private static boolean looksLikeInputTraffic(String id) {
        int separator = id.indexOf(':');
        String path = separator < 0 ? id : id.substring(separator + 1);
        return path.contains("input")
                || path.contains("key")
                || path.contains("click")
                || path.contains("toggle")
                || path.contains("disable")
                || path.contains("action")
                || path.contains("mode");
    }

    enum Mode {
        PARSED,
        PINNED_FALLBACK
    }

    record Plan(
            List<Channel> channels,
            List<Channel> clientboundBootstrapChannels,
            List<String> advertisedNamespaces,
            boolean silentGearCompatibleChannelsAdvertised,
            boolean frozenRegistryCompatibleChannelsAdvertised,
            boolean silentGearProfileSelected,
            String silentGearContractSha256,
            String silentGearRejectionReason,
            Mode mode,
            int eligibleChannelCount,
            int ignoredRegistryChannelCount,
            int omittedAutomaticChannelCount,
            String fallbackReason) {
        Plan {
            channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
            clientboundBootstrapChannels = List.copyOf(Objects.requireNonNull(
                    clientboundBootstrapChannels, "clientboundBootstrapChannels"));
            advertisedNamespaces = List.copyOf(
                    Objects.requireNonNull(advertisedNamespaces, "advertisedNamespaces"));
            Objects.requireNonNull(silentGearContractSha256, "silentGearContractSha256");
            Objects.requireNonNull(silentGearRejectionReason, "silentGearRejectionReason");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(fallbackReason, "fallbackReason");
        }
    }
}
