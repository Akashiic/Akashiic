package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import br.com.atmbrasil.lobby.velocity.protocol.ProtocolLimits;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReviewedBackendNeoForgeAdvertisementTest {
    private static final int MINECRAFT_PROTOCOL = 767;
    private static final String ATM10_NORMAL_8_0_QUERY =
            "atm10-normal/8.0/client-neoforge-response.bin";
    private static final String ATM10_NORMAL_7_3_QUERY =
            "atm10-normal/7.3/client-neoforge-response.bin";

    @Test
    void exactAtm10Normal80AdvertisementIsEligibleDespiteReviewedInvalidAe2Identifier()
            throws Exception {
        Fixture fixture = loadFixture(ATM10_NORMAL_8_0_QUERY);

        ReviewedBackendNeoForgeAdvertisement.Evidence evidence =
                ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                fixture.raw().length,
                                fixture.registry(),
                                fixture.signatures(),
                                fixture.profile())
                        .orElseThrow();

        assertAll(
                () -> assertEquals(
                        "atm10-normal-8.0-canonical-neoforge-advertisement", evidence.id()),
                () -> assertEquals(103_657, fixture.raw().length),
                () -> assertEquals(2_404, fixture.registry().declaredChannelCount()),
                () -> assertEquals(1, fixture.registry().ignoredChannelCount()),
                () -> assertEquals(List.of(expectedInvalidAe2()),
                        fixture.registry().ignoredChannels()),
                () -> assertEquals(2_403, fixture.registry().channelCount()),
                () -> assertEquals(
                        "defcad0781fa208aa5ecb9358831d140eb530a499066e8694833989fb6dbdd4c",
                        SilentGearProtocol.sha256(fixture.raw())));
    }

    @Test
    void sameIgnoredCountAndSilentGearContractDoNotAuthorizeAtm10Normal73() throws Exception {
        Fixture normal73 = loadFixture(ATM10_NORMAL_7_3_QUERY);
        Fixture normal80 = loadFixture(ATM10_NORMAL_8_0_QUERY);

        assertAll(
                () -> assertEquals(1, normal73.registry().ignoredChannelCount()),
                () -> assertEquals(
                        normal80.signatures().silentGearContractSha256(),
                        normal73.signatures().silentGearContractSha256()),
                () -> assertFalse(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                normal73.raw().length,
                                normal73.registry(),
                                normal73.signatures(),
                                normal73.profile())
                        .isPresent()));
    }

    @Test
    void channelIterationOrderDoesNotChangeCanonicalEligibility() throws Exception {
        Fixture normal80 = loadFixture(ATM10_NORMAL_8_0_QUERY);
        Map<Integer, List<Channel>> reorderedProtocols = new LinkedHashMap<>();
        normal80.registry().protocols().forEach((protocol, channels) -> {
            List<Channel> reversed = new ArrayList<>(channels);
            java.util.Collections.reverse(reversed);
            reorderedProtocols.put(protocol, List.copyOf(reversed));
        });
        Registry reordered = new Registry(
                reorderedProtocols,
                normal80.registry().declaredChannelCount(),
                normal80.registry().ignoredChannelCount(),
                normal80.registry().ignoredChannels());
        ChannelContractSignature.Signatures reorderedSignatures =
                ChannelContractSignature.from(reordered);

        assertAll(
                () -> assertEquals(normal80.signatures(), reorderedSignatures),
                () -> assertTrue(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                normal80.raw().length,
                                reordered,
                                reorderedSignatures,
                                normal80.profile())
                        .isPresent()));
    }

    @Test
    void everyLengthCountContractProtocolAndProfileBoundaryFailsClosed() throws Exception {
        Fixture normal80 = loadFixture(ATM10_NORMAL_8_0_QUERY);
        Fixture normal73 = loadFixture(ATM10_NORMAL_7_3_QUERY);
        byte[] semanticallyMutatedRaw = normal80.raw();
        semanticallyMutatedRaw[semanticallyMutatedRaw.length - 1] ^= 1;
        Registry semanticallyMutatedRegistry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                semanticallyMutatedRaw, ProtocolLimits.productionDefaults());
        ChannelContractSignature.Signatures semanticallyMutatedSignatures =
                ChannelContractSignature.from(semanticallyMutatedRegistry);
        List<IgnoredChannel> twoIgnored = List.of(
                expectedInvalidAe2(),
                new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "invalid:",
                        "1",
                        Flow.SERVERBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION));
        Registry wrongCounts = new Registry(
                normal80.registry().protocols(),
                normal80.registry().declaredChannelCount() + 1,
                normal80.registry().ignoredChannelCount() + 1,
                twoIgnored);
        ChannelContractSignature.Signatures mutatedContract =
                new ChannelContractSignature.Signatures(
                        "0" + normal80.signatures().fullContractSha256().substring(1),
                        normal80.signatures().silentGearContractSha256());

        assertAll(
                () -> assertFalse(match(normal80, normal80.raw().length - 1).isPresent()),
                () -> assertFalse(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                semanticallyMutatedRaw.length,
                                semanticallyMutatedRegistry,
                                semanticallyMutatedSignatures,
                                normal80.profile())
                        .isPresent()),
                () -> assertFalse(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL - 1,
                                normal80.raw().length,
                                normal80.registry(),
                                normal80.signatures(),
                                normal80.profile())
                        .isPresent()),
                () -> assertFalse(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                normal80.raw().length,
                                wrongCounts,
                                normal80.signatures(),
                                normal80.profile())
                        .isPresent()),
                () -> assertFalse(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                normal80.raw().length,
                                normal80.registry(),
                                mutatedContract,
                                normal80.profile())
                        .isPresent()),
                () -> assertFalse(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                normal80.raw().length,
                                normal80.registry(),
                                normal80.signatures(),
                                normal73.profile())
                        .isPresent()),
                () -> assertFalse(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                normal80.raw().length,
                                normal80.registry(),
                                normal80.signatures(),
                                null)
                        .isPresent()));
    }

    @Test
    void everyIgnoredComponentFieldIsExactAndFailsClosed() throws Exception {
        Fixture normal80 = loadFixture(ATM10_NORMAL_8_0_QUERY);
        Registry noIgnoredComponent = new Registry(
                normal80.registry().protocols(),
                normal80.registry().channelCount(),
                0,
                List.of());

        assertAll(
                () -> assertFalse(ReviewedBackendNeoForgeAdvertisement.match(
                                MINECRAFT_PROTOCOL,
                                normal80.raw().length,
                                noIgnoredComponent,
                                normal80.signatures(),
                                normal80.profile())
                        .isPresent()),
                () -> assertFalse(matchWithIgnored(normal80, new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "other:",
                        "ae2",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)).isPresent()),
                () -> assertFalse(matchWithIgnored(normal80, new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "ae2:",
                        "other",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)).isPresent()),
                () -> assertFalse(matchWithIgnored(normal80, new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "ae2:",
                        "ae2",
                        Flow.SERVERBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)).isPresent()),
                () -> assertFalse(matchWithIgnored(normal80, new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "ae2:",
                        "ae2",
                        Flow.CLIENTBOUND,
                        true,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)).isPresent()),
                () -> assertFalse(matchWithIgnored(normal80, new IgnoredChannel(
                        NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                        "ae2:",
                        "ae2",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_VERSION)).isPresent()),
                () -> assertFalse(matchWithIgnored(normal80, new IgnoredChannel(
                        NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL,
                        "ae2:",
                        "ae2",
                        Flow.CLIENTBOUND,
                        false,
                        IgnoredChannelReason.INVALID_RESOURCE_LOCATION)).isPresent()));
    }

    private static java.util.Optional<ReviewedBackendNeoForgeAdvertisement.Evidence> match(
            Fixture fixture, int rawBytes) {
        return ReviewedBackendNeoForgeAdvertisement.match(
                MINECRAFT_PROTOCOL,
                rawBytes,
                fixture.registry(),
                fixture.signatures(),
                fixture.profile());
    }

    private static java.util.Optional<ReviewedBackendNeoForgeAdvertisement.Evidence>
            matchWithIgnored(Fixture fixture, IgnoredChannel ignoredChannel) {
        Registry altered = new Registry(
                fixture.registry().protocols(),
                fixture.registry().declaredChannelCount(),
                1,
                List.of(ignoredChannel));
        return ReviewedBackendNeoForgeAdvertisement.match(
                MINECRAFT_PROTOCOL,
                fixture.raw().length,
                altered,
                fixture.signatures(),
                fixture.profile());
    }

    private static IgnoredChannel expectedInvalidAe2() {
        return new IgnoredChannel(
                NeoForgeHandshakeCodec.PLAY_PROTOCOL,
                "ae2:",
                "ae2",
                Flow.CLIENTBOUND,
                false,
                IgnoredChannelReason.INVALID_RESOURCE_LOCATION);
    }

    private Fixture loadFixture(String resource) throws Exception {
        byte[] raw = readResource(resource);
        Registry registry = NeoForgeHandshakeCodec.decodeLobbyQuery(
                raw, ProtocolLimits.productionDefaults());
        ChannelContractSignature.Signatures signatures = ChannelContractSignature.from(registry);
        SilentGearProfileCatalog catalog = SilentGearProfileCatalog.loadReviewed(
                getClass().getClassLoader(), MINECRAFT_PROTOCOL, 1_048_576, 3_145_728);
        SilentGearEmbeddedProfile profile = catalog.select(
                        MINECRAFT_PROTOCOL, signatures)
                .orElseThrow();
        return new Fixture(raw, registry, signatures, profile);
    }

    private byte[] readResource(String resource) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing test resource " + resource);
            }
            return stream.readAllBytes();
        }
    }

    private record Fixture(
            byte[] raw,
            Registry registry,
            ChannelContractSignature.Signatures signatures,
            SilentGearEmbeddedProfile profile) {
        private Fixture {
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }
    }
}
