package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolViolationException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Regression evidence from the Akashiic and Jolongaxz captures on 2026-08-24. */
final class Atm10Normal80VoiceChatContractTest {
    private static final int MINECRAFT_PROTOCOL = 767;
    private static final String QUERY_RESOURCE =
            "atm10-normal/8.0/client-neoforge-response.bin";
    private static final String BASE_HASH =
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f";
    private static final String BASE_WITHOUT_WORLD_EDIT_HASH =
            "0b4b9a75c0a92214590e30ad5bb1a09e6d9ca93bda06ac9227bec02c4cef89f2";
    private static final String AKASHIIC_HASH =
            "a6e0f7cca6d781f86b357f8e8b50e5d56a4906264b6fc6377f2ff84c87491646";
    private static final String JOLONGAXZ_HASH =
            "37d53b0f1dddc49a279e1ca419c60e9cecf56ff1f4080c8a9f88a40e80871017";

    @Test
    void auditedArtifactAndChannelTableRemainPinned() {
        assertEquals("1.21.1-2.6.22", ReviewedSimpleVoiceChatExtension.ARTIFACT_VERSION);
        assertEquals(
                "63116a4d21bd57221482d971dd85822f7c723c210b60411670cdb0aa26873cac",
                ReviewedSimpleVoiceChatExtension.ARTIFACT_SHA256);
        assertEquals("voicechat", ReviewedSimpleVoiceChatExtension.NETWORK_VERSION);
        assertEquals(20, ReviewedSimpleVoiceChatExtension.COMPATIBILITY_VERSION);
        assertEquals(
                List.of(
                        voiceServerbound("voicechat:update_state"),
                        voiceClientbound("voicechat:state"),
                        voiceClientbound("voicechat:states"),
                        voiceClientbound("voicechat:remove_state"),
                        voiceClientbound("voicechat:secret"),
                        voiceServerbound("voicechat:request_secret"),
                        voiceClientbound("voicechat:add_group"),
                        voiceClientbound("voicechat:remove_group"),
                        voiceServerbound("voicechat:set_group"),
                        voiceServerbound("voicechat:create_group"),
                        voiceServerbound("voicechat:leave_group"),
                        voiceClientbound("voicechat:joined_group"),
                        voiceClientbound("voicechat:add_category"),
                        voiceClientbound("voicechat:remove_category")),
                ReviewedSimpleVoiceChatExtension.channels());
        assertEquals(14, ReviewedSimpleVoiceChatExtension.channelIds().size());
        assertEquals(16, ReviewedSimpleVoiceChatExtension.externallyOwnedChannelIds().size());
        assertFalse(ReviewedSimpleVoiceChatExtension.channelIds().contains("vc:secret"));
        assertFalse(ReviewedSimpleVoiceChatExtension.channelIds().contains("vc:request_secret"));
        assertTrue(ReviewedSimpleVoiceChatExtension.externallyOwns("vc:secret"));
        assertTrue(ReviewedSimpleVoiceChatExtension.externallyOwns("vc:request_secret"));
    }

    @Test
    void exactObservedVoiceVariantsNormalizeToTheImmutableNormal80Profile() throws Exception {
        Registry base = baseRegistry();
        Registry akashiic = withVoiceChat(base, true);
        Registry jolongaxz = withVoiceChat(base, false);
        ChannelContractSignature.Signatures akashiicSignatures = signature(akashiic);
        ChannelContractSignature.Signatures jolongaxzSignatures = signature(jolongaxz);

        ReviewedClientContractEvidence.Atm10Normal80Variant akashiicVariant =
                ReviewedClientContractEvidence.matchAtm10Normal80(
                                MINECRAFT_PROTOCOL, akashiic, akashiicSignatures)
                        .orElseThrow();
        ReviewedClientContractEvidence.Atm10Normal80Variant jolongaxzVariant =
                ReviewedClientContractEvidence.matchAtm10Normal80(
                                MINECRAFT_PROTOCOL, jolongaxz, jolongaxzSignatures)
                        .orElseThrow();
        SilentGearProfileCatalog catalog = reviewedCatalog();

        assertEquals(AKASHIIC_HASH, akashiicSignatures.fullContractSha256());
        assertEquals(JOLONGAXZ_HASH, jolongaxzSignatures.fullContractSha256());
        assertEquals(2_418, akashiic.declaredChannelCount());
        assertEquals(2_417, akashiic.channelCount());
        assertEquals(2_417, jolongaxz.declaredChannelCount());
        assertEquals(2_416, jolongaxz.channelCount());
        assertTrue(akashiicVariant.worldEditCuiPresent());
        assertFalse(jolongaxzVariant.worldEditCuiPresent());
        assertTrue(akashiicVariant.simpleVoiceChatPresent());
        assertTrue(jolongaxzVariant.simpleVoiceChatPresent());
        assertEquals(BASE_HASH, akashiicVariant.normalizedFullClientContractSha256());
        assertEquals(BASE_HASH, jolongaxzVariant.normalizedFullClientContractSha256());
        assertEquals(14, akashiicVariant.externallyOwnedPlayChannels().size());
        assertEquals(14, jolongaxzVariant.externallyOwnedPlayChannels().size());
        assertEquals(
                "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                catalog.select(MINECRAFT_PROTOCOL, akashiic, akashiicSignatures)
                        .orElseThrow()
                        .profileId());
        assertEquals(
                "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                catalog.select(MINECRAFT_PROTOCOL, jolongaxz, jolongaxzSignatures)
                        .orElseThrow()
                        .profileId());
    }

    @Test
    void pureAndOptionalWorldEditAbsentVariantsRemainAccepted() throws Exception {
        Registry base = baseRegistry();
        Registry withoutWorldEdit = withoutWorldEditCui(base);
        ChannelContractSignature.Signatures baseSignatures = signature(base);
        ChannelContractSignature.Signatures noWorldEditSignatures = signature(withoutWorldEdit);
        SilentGearProfileCatalog catalog = reviewedCatalog();

        assertEquals(BASE_HASH, baseSignatures.fullContractSha256());
        assertEquals(BASE_WITHOUT_WORLD_EDIT_HASH, noWorldEditSignatures.fullContractSha256());
        assertTrue(ReviewedClientContractEvidence.matchAtm10Normal80(
                        MINECRAFT_PROTOCOL, base, baseSignatures)
                .isPresent());
        assertTrue(ReviewedClientContractEvidence.matchAtm10Normal80(
                        MINECRAFT_PROTOCOL, withoutWorldEdit, noWorldEditSignatures)
                .isPresent());
        assertTrue(catalog.select(MINECRAFT_PROTOCOL, base, baseSignatures).isPresent());
        assertTrue(catalog.select(
                        MINECRAFT_PROTOCOL, withoutWorldEdit, noWorldEditSignatures)
                .isPresent());
    }

    @Test
    void everyVoiceChatContractDimensionRemainsAtomicAndFailClosed() throws Exception {
        Registry exact = withVoiceChat(baseRegistry(), true);
        Channel first = ReviewedSimpleVoiceChatExtension.channels().getFirst();

        List<Registry> mutations = List.of(
                mutatePlay(exact, channels -> channels.stream()
                        .filter(channel -> !channel.id().equals(first.id()))
                        .toList()),
                mutatePlay(exact, channels -> replace(channels, first.id(),
                        new Channel(first.id(), "changed", first.flow(), first.optional()))),
                mutatePlay(exact, channels -> replace(channels, first.id(),
                        new Channel(first.id(), first.version(), Flow.CLIENTBOUND,
                                first.optional()))),
                mutatePlay(exact, channels -> replace(channels, first.id(),
                        new Channel(first.id(), first.version(), first.flow(), false))),
                mutatePlay(exact, channels -> append(channels,
                        new Channel("voicechat:unreviewed", "voicechat",
                                Flow.SERVERBOUND, true))),
                mutatePlay(exact, channels -> channels.stream()
                        .filter(channel -> !channel.id().equals("silentgear:sync_traits"))
                        .toList()),
                addConfigurationChannel(exact,
                        new Channel("voicechat:configuration", "voicechat",
                                Flow.BIDIRECTIONAL, true)));

        SilentGearProfileCatalog catalog = reviewedCatalog();
        for (Registry mutation : mutations) {
            ChannelContractSignature.Signatures signatures = signature(mutation);
            assertTrue(ReviewedClientContractEvidence.matchAtm10Normal80(
                            MINECRAFT_PROTOCOL, mutation, signatures)
                    .isEmpty());
            assertTrue(catalog.select(MINECRAFT_PROTOCOL, mutation, signatures).isEmpty());
        }
    }

    @Test
    void voiceExtensionMatcherRejectsEveryPartialOrCrossPhaseShape() throws Exception {
        Registry base = baseRegistry();
        Registry exact = withVoiceChat(base, true);
        Channel first = ReviewedSimpleVoiceChatExtension.channels().getFirst();
        List<Registry> mutations = List.of(
                mutatePlay(exact, channels -> channels.stream()
                        .filter(channel -> !channel.id().equals(first.id()))
                        .toList()),
                mutatePlay(exact, channels -> replace(channels, first.id(),
                        new Channel(first.id(), "changed", first.flow(), first.optional()))),
                mutatePlay(exact, channels -> replace(channels, first.id(),
                        new Channel(first.id(), first.version(), Flow.CLIENTBOUND,
                                first.optional()))),
                mutatePlay(exact, channels -> replace(channels, first.id(),
                        new Channel(first.id(), first.version(), first.flow(), false))),
                mutatePlay(exact, channels -> append(channels,
                        new Channel("voicechat:unreviewed", "voicechat",
                                Flow.SERVERBOUND, true))),
                addConfigurationChannel(exact,
                        new Channel("voicechat:configuration", "voicechat",
                                Flow.BIDIRECTIONAL, true)));

        assertTrue(ReviewedSimpleVoiceChatExtension.matches(exact));
        assertFalse(ReviewedSimpleVoiceChatExtension.absent(exact));
        assertTrue(ReviewedSimpleVoiceChatExtension.absent(base));
        for (Registry mutation : mutations) {
            assertFalse(ReviewedSimpleVoiceChatExtension.matches(mutation));
            assertFalse(ReviewedSimpleVoiceChatExtension.absent(mutation));
        }
    }

    @Test
    void capturedDeltasContainOnlyVoiceChannelsAndOptionalWorldEditCui() throws Exception {
        Registry base = baseRegistry();
        Registry akashiic = withVoiceChat(base, true);
        Registry jolongaxz = withVoiceChat(base, false);

        assertEquals(
                ReviewedSimpleVoiceChatExtension.channels(),
                addedPlayChannels(base, akashiic));
        assertEquals(
                base.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL),
                akashiic.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL));
        assertEquals(
                List.of(new Channel("worldedit:cui", "1", Flow.BIDIRECTIONAL, true)),
                addedPlayChannels(jolongaxz, akashiic));
        assertEquals(
                jolongaxz.protocols(),
                withoutWorldEditCui(akashiic).protocols());
        assertEquals(jolongaxz.ignoredChannels(), akashiic.ignoredChannels());
    }

    @Test
    void voiceVariantsRequireRegistryAwareSelectionAndExactMinecraftProtocol()
            throws Exception {
        Registry exact = withVoiceChat(baseRegistry(), true);
        ChannelContractSignature.Signatures signatures = signature(exact);
        SilentGearProfileCatalog catalog = reviewedCatalog();

        assertTrue(catalog.select(MINECRAFT_PROTOCOL, signatures).isEmpty());
        assertTrue(catalog.select(MINECRAFT_PROTOCOL, exact, signatures).isPresent());
        for (int wrongProtocol : List.of(MINECRAFT_PROTOCOL - 1, MINECRAFT_PROTOCOL + 1)) {
            assertTrue(ReviewedClientContractEvidence.matchAtm10Normal80(
                            wrongProtocol, exact, signatures)
                    .isEmpty());
            assertTrue(catalog.select(wrongProtocol, exact, signatures).isEmpty());
            assertFalse(ReviewedClientContractEvidence.matchesAtm10Normal80(
                    wrongProtocol, signatures));
        }
    }

    @Test
    void knownHashWithAlteredIgnoredComponentShapeCannotUseTheProfile() throws Exception {
        Registry exact = withVoiceChat(baseRegistry(), true);
        List<IgnoredChannel> ignored = List.of(
                exact.ignoredChannels().getFirst(),
                new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "invalid:",
                        "1",
                        Flow.SERVERBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION));
        Registry altered = new Registry(
                exact.protocols(), exact.channelCount() + ignored.size(), ignored.size(), ignored);
        ChannelContractSignature.Signatures signatures = signature(altered);

        assertEquals(AKASHIIC_HASH, signatures.fullContractSha256());
        assertTrue(ReviewedClientContractEvidence.matchAtm10Normal80(
                        MINECRAFT_PROTOCOL, altered, signatures)
                .isEmpty());
        assertTrue(reviewedCatalog().select(MINECRAFT_PROTOCOL, altered, signatures).isEmpty());
    }

    @Test
    void voiceChannelsAreNegotiatedButNeverReservedAsProtocolObeliskSinks() throws Exception {
        Registry exact = withVoiceChat(baseRegistry(), true);
        PlaySinkPlanner.Plan plan = PlaySinkPlanner.plan(
                MINECRAFT_PROTOCOL,
                exact,
                List.of(),
                4_096,
                false,
                true,
                true,
                ReviewedSimpleVoiceChatExtension.channelIds());

        assertTrue(plan.channels().stream()
                .noneMatch(channel -> ReviewedSimpleVoiceChatExtension.channelIds()
                        .contains(channel.id())));
        List<Channel> negotiated = Atm10LobbyVelocityPlugin.negotiatedPlayChannels(
                plan.channels(), List.of(), ReviewedSimpleVoiceChatExtension.channels());
        assertTrue(negotiated.containsAll(ReviewedSimpleVoiceChatExtension.channels()));
        assertEquals(
                ReviewedSimpleVoiceChatExtension.channels(),
                ReviewedClientContractEvidence.matchAtm10Normal80(
                                MINECRAFT_PROTOCOL, exact, signature(exact))
                        .orElseThrow()
                        .externallyOwnedPlayChannels());

        Channel serverboundVoice = ReviewedSimpleVoiceChatExtension.channels().stream()
                .filter(channel -> channel.flow() == Flow.SERVERBOUND)
                .findFirst()
                .orElseThrow();
        PlaySinkPlanner.Plan poisoningAttempt = PlaySinkPlanner.plan(
                MINECRAFT_PROTOCOL,
                exact,
                List.of(new PinnedPlayChannel(
                        serverboundVoice.id(), serverboundVoice.version())),
                4_096,
                false,
                true,
                true,
                Set.of());
        assertTrue(poisoningAttempt.channels().stream()
                .noneMatch(channel -> ReviewedSimpleVoiceChatExtension.externallyOwns(
                        channel.id())));
        assertThrows(
                ProtocolViolationException.class,
                () -> ReviewedSimpleVoiceChatExtension
                        .requireProtocolObeliskSinkOwnership(serverboundVoice.id()));
        assertThrows(
                ProtocolViolationException.class,
                () -> ReviewedSimpleVoiceChatExtension
                        .requireProtocolObeliskSinkOwnership("vc:request_secret"));

        Channel legacyAlias =
                new Channel("vc:request_secret", "voicechat", Flow.SERVERBOUND, true);
        Registry legacyPoisoningRegistry = mutatePlay(
                exact, channels -> append(channels, legacyAlias));
        PlaySinkPlanner.Plan legacyPoisoningAttempt = PlaySinkPlanner.plan(
                MINECRAFT_PROTOCOL,
                legacyPoisoningRegistry,
                List.of(new PinnedPlayChannel(legacyAlias.id(), legacyAlias.version())),
                4_096,
                false,
                false,
                false,
                Set.of());
        assertTrue(legacyPoisoningAttempt.channels().stream()
                .noneMatch(channel -> channel.id().equals(legacyAlias.id())));

        List<String> configNamespaces = Atm10LobbyVelocityPlugin.configDerivationNamespaces(
                legacyPoisoningAttempt.advertisedNamespaces(), Set.of());
        assertFalse(configNamespaces.contains("voicechat"));
        assertFalse(configNamespaces.contains("vc"));
        assertTrue(TransientServerConfigPlanner.plan(
                        List.of(), configNamespaces, true, 4_096)
                .configs().stream()
                .noneMatch(config -> config.equals("voicechat-server.toml")));
    }

    @Test
    void listenerAndReservationKeepTheExternalOwnershipFenceAheadOfGlobalSinks()
            throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/br/com/atmbrasil/lobby/velocity/"
                        + "Atm10LobbyVelocityPlugin.java"),
                StandardCharsets.UTF_8);
        int listener = source.indexOf("public void onPluginMessage(");
        int externalPassThrough = source.indexOf(
                "ReviewedSimpleVoiceChatExtension.externallyOwns(channelId)", listener);
        int activeConfigLookup = source.indexOf("BridgeConfig currentConfig", listener);
        int sessionOwnershipLookup = source.indexOf(
                "LobbyPlaySinkAdmissionPolicy.consumesPlayerPayload(", listener);
        int handled = source.indexOf(
                "event.setResult(PluginMessageEvent.ForwardResult.handled())",
                sessionOwnershipLookup);
        assertTrue(listener >= 0
                && externalPassThrough > listener
                && activeConfigLookup > externalPassThrough
                && sessionOwnershipLookup > activeConfigLookup
                && handled > sessionOwnershipLookup);

        int reservation = source.indexOf("private PlaySinkReservation reservePlaySinkChannels(");
        int ownershipGuard = source.indexOf(
                "requireProtocolObeliskSinkOwnership(channel.id())", reservation);
        int registrarWrite = source.indexOf("getChannelRegistrar().register", reservation);
        assertTrue(reservation >= 0
                && ownershipGuard > reservation
                && registrarWrite > ownershipGuard);
    }

    @Test
    void reviewedBackendRelayEvidenceCoversBothLiveVoiceAdvertisements() throws Exception {
        SilentGearProfileCatalog catalog = reviewedCatalog();
        Registry akashiic = withVoiceChat(baseRegistry(), true);
        Registry jolongaxz = withVoiceChat(baseRegistry(), false);

        assertEquals(
                "atm10-normal-8.0-simple-voice-chat-neoforge-advertisement",
                backendEvidence(catalog, akashiic, 104_140).id());
        assertEquals(
                "atm10-normal-8.0-simple-voice-chat-without-worldedit-cui-neoforge-"
                        + "advertisement",
                backendEvidence(catalog, jolongaxz, 104_122).id());
        assertTrue(ReviewedBackendNeoForgeAdvertisement.match(
                        MINECRAFT_PROTOCOL,
                        104_139,
                        akashiic,
                        signature(akashiic),
                        catalog.select(MINECRAFT_PROTOCOL, akashiic, signature(akashiic))
                                .orElseThrow())
                .isEmpty());
    }

    @Test
    void auditedVoiceChannelWireFootprintIsExactlyTheObservedDelta() {
        int bytes = ReviewedSimpleVoiceChatExtension.channels().stream()
                .mapToInt(Atm10Normal80VoiceChatContractTest::wireBytes)
                .sum();

        assertEquals(14, ReviewedSimpleVoiceChatExtension.channels().size());
        assertEquals(483, bytes);
        assertEquals(ReviewedSimpleVoiceChatExtension.CANONICAL_WIRE_BYTES, bytes);
        assertEquals(104_140, 103_657 + bytes);
        assertEquals(104_122, 103_657 + bytes - wireBytes(
                new Channel("worldedit:cui", "1", Flow.BIDIRECTIONAL, true)));
    }

    private ReviewedBackendNeoForgeAdvertisement.Evidence backendEvidence(
            SilentGearProfileCatalog catalog, Registry registry, int rawBytes) {
        ChannelContractSignature.Signatures signatures = signature(registry);
        SilentGearEmbeddedProfile profile = catalog.select(
                        MINECRAFT_PROTOCOL, registry, signatures)
                .orElseThrow();
        return ReviewedBackendNeoForgeAdvertisement.match(
                        MINECRAFT_PROTOCOL, rawBytes, registry, signatures, profile)
                .orElseThrow();
    }

    private Registry baseRegistry() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(QUERY_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing Normal 8.0 query fixture");
            }
            return NeoForgeHandshakeCodec.decodeLobbyQuery(
                    stream.readAllBytes(), ProtocolLimits.productionDefaults());
        }
    }

    private SilentGearProfileCatalog reviewedCatalog() throws Exception {
        return SilentGearProfileCatalog.loadReviewed(
                getClass().getClassLoader(), MINECRAFT_PROTOCOL, 1_048_576, 3_145_728);
    }

    private static Registry withVoiceChat(Registry base, boolean worldEditCui) {
        Registry foundation = worldEditCui ? base : withoutWorldEditCui(base);
        return mutatePlay(foundation,
                channels -> append(channels, ReviewedSimpleVoiceChatExtension.channels()));
    }

    private static Registry withoutWorldEditCui(Registry registry) {
        return mutatePlay(registry, channels -> channels.stream()
                .filter(channel -> !channel.id().equals("worldedit:cui"))
                .toList());
    }

    private static Registry mutatePlay(
            Registry registry, UnaryOperator<List<Channel>> mutation) {
        Map<Integer, List<Channel>> protocols = new LinkedHashMap<>(registry.protocols());
        protocols.put(
                NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                List.copyOf(mutation.apply(new ArrayList<>(registry.channelsFor(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL)))));
        int usable = protocols.values().stream().mapToInt(List::size).sum();
        return new Registry(
                protocols,
                usable + registry.ignoredChannelCount(),
                registry.ignoredChannelCount(),
                registry.ignoredChannels());
    }

    private static Registry addConfigurationChannel(Registry registry, Channel channel) {
        Map<Integer, List<Channel>> protocols = new LinkedHashMap<>(registry.protocols());
        protocols.put(
                NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL,
                append(registry.channelsFor(NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL),
                        channel));
        int usable = protocols.values().stream().mapToInt(List::size).sum();
        return new Registry(
                protocols,
                usable + registry.ignoredChannelCount(),
                registry.ignoredChannelCount(),
                registry.ignoredChannels());
    }

    private static List<Channel> replace(
            List<Channel> channels, String id, Channel replacement) {
        return channels.stream()
                .map(channel -> channel.id().equals(id) ? replacement : channel)
                .toList();
    }

    private static <T> List<T> append(List<T> left, T right) {
        return append(left, List.of(right));
    }

    private static <T> List<T> append(List<T> left, List<T> right) {
        ArrayList<T> result = new ArrayList<>(left.size() + right.size());
        result.addAll(left);
        result.addAll(right);
        return List.copyOf(result);
    }

    private static ChannelContractSignature.Signatures signature(Registry registry) {
        return ChannelContractSignature.from(registry);
    }

    private static List<Channel> addedPlayChannels(Registry before, Registry after) {
        Set<Channel> existing = Set.copyOf(before.channelsFor(
                NeoForgeHandshakeCodec.PLAY_PROTOCOL));
        return after.channelsFor(NeoForgeHandshakeCodec.PLAY_PROTOCOL).stream()
                .filter(channel -> !existing.contains(channel))
                .toList();
    }

    private static Channel voiceServerbound(String id) {
        return new Channel(id, "voicechat", Flow.SERVERBOUND, true);
    }

    private static Channel voiceClientbound(String id) {
        return new Channel(id, "voicechat", Flow.CLIENTBOUND, true);
    }

    private static int wireBytes(Channel channel) {
        int idBytes = channel.id().getBytes(StandardCharsets.UTF_8).length;
        int versionBytes = channel.version().getBytes(StandardCharsets.UTF_8).length;
        return varIntBytes(idBytes) + idBytes
                + varIntBytes(versionBytes) + versionBytes
                + 1
                + (channel.flow() == Flow.BIDIRECTIONAL ? 0 : 1)
                + 1;
    }

    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            bytes++;
            value >>>= 7;
        }
        return bytes;
    }
}
