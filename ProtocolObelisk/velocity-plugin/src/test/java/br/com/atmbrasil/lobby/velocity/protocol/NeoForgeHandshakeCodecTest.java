package br.com.atmbrasil.lobby.velocity.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NeoForgeHandshakeCodecTest {
    private static final ProtocolLimits LIMITS = ProtocolLimits.productionDefaults();

    @Test
    void decodesExactQueryShapeAndEncodesNetworkPayloadSetup() throws Exception {
        byte[] query = query(
                protocol(4, channel("test:config", "v", Flow.BIDIRECTIONAL, true)),
                protocol(1, channel("test:ping", "1", Flow.SERVERBOUND, false)));

        Registry registry = NeoForgeHandshakeCodec.decodeQuery(query, LIMITS);

        assertEquals(2, registry.channelCount());
        assertEquals(Set.of("test:config", "test:ping"), registry.channelIds());
        assertEquals(Flow.SERVERBOUND, registry.channelsFor(1).getFirst().flow());
        assertEquals(Flow.BIDIRECTIONAL, registry.channelsFor(4).getFirst().flow());

        byte[] expectedSetup = hex("""
                02
                01 01
                09 746573743a70696e67
                09 746573743a70696e67
                01 31
                04 01
                0b 746573743a636f6e666967
                0b 746573743a636f6e666967
                01 76
                """);
        assertArrayEquals(expectedSetup, NeoForgeHandshakeCodec.encodeSetup(registry, LIMITS));
    }

    @Test
    void emptySetupHasExactGoldenVectorAndReturnedArraysAreIndependent() {
        byte[] setup = NeoForgeHandshakeCodec.emptySetup();
        assertArrayEquals(new byte[] {0}, setup);
        setup[0] = 1;
        assertArrayEquals(new byte[] {0}, NeoForgeHandshakeCodec.emptySetup());
    }

    @Test
    void encodesMinimalNeoForgeConfigurationLifecycleWithGoldenVectors() throws Exception {
        Registry setup = new Registry(Map.of(
                NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL,
                List.of(new NeoForgeHandshakeCodec.Channel(
                        "neoforge:config_file", "1", Flow.CLIENTBOUND, true))));
        byte[] expectedSetup = hex("""
                01 04 01
                14 6e656f666f7267653a636f6e6669675f66696c65
                14 6e656f666f7267653a636f6e6669675f66696c65
                01 31
                """);
        assertArrayEquals(expectedSetup, NeoForgeHandshakeCodec.encodeSetup(setup, LIMITS));

        byte[] expectedConfig = hex("""
                19 736563757269747963726166742d7365727665722e746f6d6c
                00
                """);
        byte[] encodedConfig = NeoForgeHandshakeCodec.encodeConfigFilePayload(
                "securitycraft-server.toml", new byte[0], expectedConfig.length);
        assertArrayEquals(expectedConfig, encodedConfig);

        byte[] expectedNestedConfig = hex("""
                17 6172735f6e6f75766561752f726577696e642e746f6d6c
                00
                """);
        assertArrayEquals(
                expectedNestedConfig,
                NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "ars_nouveau/rewind.toml", new byte[0], expectedNestedConfig.length));
        byte[] expectedCaseSensitiveConfig = hex("""
                15 4d656b616e69736d2f67656e6572616c2e746f6d6c
                00
                """);
        assertArrayEquals(
                expectedCaseSensitiveConfig,
                NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "Mekanism/general.toml",
                        new byte[0],
                        expectedCaseSensitiveConfig.length));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "securitycraft-server.toml", new byte[0], expectedConfig.length - 1));
    }

    @Test
    void transientConfigPayloadRejectsFilesystemAndNonTomlNames() {
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "../securitycraft-server.toml", new byte[0], 1_024));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "SecurityCraft server.toml", new byte[0], 1_024));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "securitycraft-server.txt", new byte[0], 1_024));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "/ars_nouveau/rewind.toml", new byte[0], 1_024));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "ars_nouveau//rewind.toml", new byte[0], 1_024));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.encodeConfigFilePayload(
                        "ars_nouveau\\rewind.toml", new byte[0], 1_024));
    }

    @Test
    void minimalChannelLobbyTreatsClientRegistryAsBoundedOpaqueData() throws Exception {
        byte[] modOwnedShape = query(protocol(
                1, channel("MOD-SPECIFIC:NonCanonical", " any version ", Flow.SERVERBOUND, false)));

        // The forensic decoder remains deliberately strict, but the runtime lobby path must not
        // depend on identifiers or versions it never uses.
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeQuery(modOwnedShape, LIMITS));
        NeoForgeHandshakeCodec.validateOpaqueQueryResponse(modOwnedShape, LIMITS);

        byte[] maximumSizedResponse = new byte[LIMITS.maximumQueryBytes()];
        maximumSizedResponse[0] = 1;
        NeoForgeHandshakeCodec.validateOpaqueQueryResponse(maximumSizedResponse, LIMITS);
    }

    @Test
    void lobbyDecoderRecoversAroundSemanticallyUnusableComponents() throws Exception {
        byte[] payload = query(protocol(1,
                channel("INVALID:component", "1", Flow.SERVERBOUND, false),
                channel("edgecase:", "1", Flow.SERVERBOUND, false),
                channel("sophisticatedstorage:request_player_settings", "1.0",
                        Flow.SERVERBOUND, false)));

        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeQuery(payload, LIMITS));

        Registry registry = NeoForgeHandshakeCodec.decodeLobbyQuery(payload, LIMITS);

        assertEquals(3, registry.declaredChannelCount());
        assertEquals(2, registry.ignoredChannelCount());
        assertEquals(List.of(
                        new IgnoredChannel(
                                NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                                "INVALID:component",
                                "1",
                                Flow.SERVERBOUND,
                                false,
                                IgnoredChannelReason.INVALID_RESOURCE_LOCATION),
                        new IgnoredChannel(
                                NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                                "edgecase:",
                                "1",
                                Flow.SERVERBOUND,
                                false,
                                IgnoredChannelReason.INVALID_RESOURCE_LOCATION)),
                registry.ignoredChannels());
        assertEquals(1, registry.channelCount());
        assertEquals(
                Set.of("sophisticatedstorage:request_player_settings"),
                registry.channelIds());
    }

    @Test
    void lobbyDecoderRejectsEmptyResourceLocationComponents() throws Exception {
        byte[] payload = query(protocol(1,
                channel("", "1", Flow.SERVERBOUND, false),
                channel(":path", "1", Flow.SERVERBOUND, false),
                channel("namespace:", "1", Flow.SERVERBOUND, false),
                channel("minecraft:valid", "1", Flow.SERVERBOUND, false)));

        Registry registry = NeoForgeHandshakeCodec.decodeLobbyQuery(payload, LIMITS);

        assertEquals(4, registry.declaredChannelCount());
        assertEquals(3, registry.ignoredChannelCount());
        assertEquals(List.of("", ":path", "namespace:"),
                registry.ignoredChannels().stream().map(IgnoredChannel::id).toList());
        assertEquals(List.of(
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION),
                registry.ignoredChannels().stream().map(IgnoredChannel::reason).toList());
        assertEquals(Set.of("minecraft:valid"), registry.channelIds());
    }

    @Test
    void opaqueLobbyPayloadValidationEnforcesExactByteBoundaries() throws Exception {
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.validateOpaqueQueryResponse(new byte[0], LIMITS));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.validateOpaqueQueryResponse(
                        new byte[LIMITS.maximumQueryBytes() + 1], LIMITS));

        NeoForgeHandshakeCodec.validateOpaqueControlPayload(
                new byte[0], LIMITS.maximumSetupBytes());
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.validateOpaqueControlPayload(
                        new byte[LIMITS.maximumSetupBytes() + 1],
                        LIMITS.maximumSetupBytes()));
    }

    @Test
    void rejectsTrailingBytesOverlongVarIntAndDuplicateProtocol() throws Exception {
        byte[] valid = query(protocol(1, channel("test:ping", "1", Flow.SERVERBOUND, false)));
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeQuery(trailing, LIMITS));

        byte[] overlongMapSize = java.util.Arrays.copyOf(valid, valid.length + 1);
        System.arraycopy(valid, 1, overlongMapSize, 2, valid.length - 1);
        overlongMapSize[0] = (byte) 0x81;
        overlongMapSize[1] = 0;
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeQuery(overlongMapSize, LIMITS));

        byte[] duplicate = query(
                protocol(1, channel("test:one", "1", Flow.SERVERBOUND, false)),
                protocol(1, channel("test:two", "1", Flow.SERVERBOUND, false)));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeQuery(duplicate, LIMITS));
    }

    @Test
    void rejectsInvalidIdsVersionsBooleansAndBounds() throws Exception {
        assertThrows(ProtocolViolationException.class, () -> NeoForgeHandshakeCodec.decodeQuery(
                query(protocol(1, channel("INVALID:channel", "1", Flow.SERVERBOUND, false))),
                LIMITS));
        assertThrows(ProtocolViolationException.class, () -> NeoForgeHandshakeCodec.decodeQuery(
                query(protocol(1, channel("test:channel", " 1", Flow.SERVERBOUND, false))),
                LIMITS));

        byte[] valid = query(protocol(1, channel("test:ping", "1", Flow.BIDIRECTIONAL, false)));
        valid[valid.length - 1] = 2;
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeQuery(valid, LIMITS));

        ProtocolLimits oneChannel = new ProtocolLimits(32_767, 1_048_576, 2, 1, 1, 256, 256);
        byte[] twoChannels = query(protocol(1,
                channel("test:one", "1", Flow.SERVERBOUND, false),
                channel("test:two", "1", Flow.SERVERBOUND, false)));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeQuery(twoChannels, oneChannel));
    }

    @Test
    void dinnerboneAndCommonRegisterCodecsAreBounded() throws Exception {
        Set<String> channels = Set.of("test:one", "test:two");
        byte[] encoded = NeoForgeHandshakeCodec.encodeDinnerboneChannels(channels, 256);
        assertEquals(channels,
                NeoForgeHandshakeCodec.decodeDinnerboneChannels(encoded, 2, 256));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeDinnerboneChannels(encoded, 1, 256));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeDinnerboneChannels(encoded, 2, 4));

        ByteArrayOutputStream common = new ByteArrayOutputStream();
        writeVarInt(common, 1);
        writeString(common, "play");
        writeVarInt(common, 2);
        writeString(common, "test:one");
        writeString(common, "test:two");
        assertEquals(channels,
                NeoForgeHandshakeCodec.decodeCommonRegister(common.toByteArray(), LIMITS));

        ByteArrayOutputStream configuration = new ByteArrayOutputStream();
        writeVarInt(configuration, 1);
        writeString(configuration, "configuration");
        writeVarInt(configuration, 0);
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeCommonRegister(
                        configuration.toByteArray(), LIMITS));
    }

    @Test
    void lateChannelAdvertisementsMustBeDeclaredByInitialQuery() throws Exception {
        Registry registry = NeoForgeHandshakeCodec.decodeQuery(
                query(protocol(1,
                        channel("test:declared", "1", Flow.SERVERBOUND, true))),
                LIMITS);

        NeoForgeHandshakeCodec.requireInitiallyDeclaredChannels(
                Set.of("test:declared", "minecraft:register"),
                registry,
                Set.of("minecraft:register"));
        assertThrows(ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.requireInitiallyDeclaredChannels(
                        Set.of("attacker:invented"),
                        registry,
                        Set.of("minecraft:register")));
    }

    @Test
    void queryRequestIsTheEncodedEmptyMap() {
        assertArrayEquals(new byte[] {0}, NeoForgeHandshakeCodec.queryRequest());
    }

    @Test
    void productionLimitAcceptsRegistryLargerThanLegacyThirtyTwoKilobytes() throws Exception {
        List<TestChannel> channels = new ArrayList<>();
        for (int index = 0; index < 1_200; index++) {
            channels.add(channel(
                    "largepack:network_channel_" + index,
                    "version-for-large-registry-" + index,
                    Flow.BIDIRECTIONAL,
                    true));
        }
        byte[] payload = query(protocol(1, channels.toArray(TestChannel[]::new)));
        org.junit.jupiter.api.Assertions.assertTrue(payload.length > 32_767);
        assertEquals(1_200, NeoForgeHandshakeCodec.decodeQuery(payload, LIMITS).channelCount());

        ProtocolLimits legacyLimit =
                new ProtocolLimits(32_767, 1_048_576, 2, 16_384, 16_384, 256, 256);
        ProtocolViolationException rejection = assertThrows(
                ProtocolViolationException.class,
                () -> NeoForgeHandshakeCodec.decodeQuery(payload, legacyLimit));
        org.junit.jupiter.api.Assertions.assertTrue(rejection.getMessage().contains(
                Integer.toString(payload.length)));
    }

    @Test
    void randomUntrustedInputsEitherDecodeOrFailWithTypedViolation() {
        Random random = new Random(0xA7_10_73L);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            byte[] payload = new byte[random.nextInt(513)];
            random.nextBytes(payload);
            try {
                NeoForgeHandshakeCodec.decodeQuery(payload, LIMITS);
            } catch (ProtocolViolationException expected) {
                // Typed, bounded rejection is the required fail-closed result.
            }
            try {
                NeoForgeHandshakeCodec.decodeDinnerboneChannels(payload, 128, 512);
            } catch (ProtocolViolationException expected) {
                // Typed, bounded rejection is the required fail-closed result.
            }
        }
    }

    private static byte[] query(Protocol... protocols) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeVarInt(output, protocols.length);
        for (Protocol protocol : protocols) {
            writeVarInt(output, protocol.ordinal());
            writeVarInt(output, protocol.channels().length);
            for (TestChannel channel : protocol.channels()) {
                writeString(output, channel.id());
                writeString(output, channel.version());
                if (channel.flow() == Flow.BIDIRECTIONAL) {
                    output.write(0);
                } else {
                    output.write(1);
                    writeVarInt(output, channel.flow().wireOrdinal());
                }
                output.write(channel.optional() ? 1 : 0);
            }
        }
        return output.toByteArray();
    }

    private static Protocol protocol(int ordinal, TestChannel... channels) {
        return new Protocol(ordinal, channels);
    }

    private static TestChannel channel(String id, String version, Flow flow, boolean optional) {
        return new TestChannel(id, version, flow, optional);
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        do {
            int next = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                next |= 0x80;
            }
            output.write(next);
        } while (remaining != 0);
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value.replaceAll("\\s+", ""));
    }

    private record Protocol(int ordinal, TestChannel[] channels) {
    }

    private record TestChannel(String id, String version, Flow flow, boolean optional) {
    }
}
