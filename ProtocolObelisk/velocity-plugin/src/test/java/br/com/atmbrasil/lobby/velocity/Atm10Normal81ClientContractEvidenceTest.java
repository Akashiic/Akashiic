package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Channel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Flow;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannel;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.IgnoredChannelReason;
import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec.Registry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Synthetic, adversarial tests of optional-channel normalization, NOT an ATM10 8.1 query capture.
 * The production 8.1 SHA remains independently pinned and never accepts this synthetic fixture.
 */
final class Atm10Normal81ClientContractEvidenceTest {
    private static final int PLAY = NeoForgeHandshakeCodec.PLAY_PROTOCOL;
    private static final int CONFIGURATION = NeoForgeHandshakeCodec.CONFIGURATION_PROTOCOL;
    private static final Channel CUI = new Channel("worldedit:cui", "1", Flow.BIDIRECTIONAL, true);
    private static final String SYNTHETIC_FULL_SHA =
            "a07d145f03d111d8bc146e257b8b5b3a1983dd7c4002fd06c13b25d4d5820100";
    private static final String SYNTHETIC_SILENT_GEAR_SHA =
            "b7dc60a2f47e4b99b14c0696cfd415b4761fedd3654b5492ac582b147bd12466";

    @Test
    void allFourOptionalCombinationsResolveOnlyAgainstAnExactWholeContract() {
        Registry canonical = fixture(true, true);
        assertEquals(SYNTHETIC_FULL_SHA, ChannelContractSignature.from(canonical).fullContractSha256());
        assertEquals(SYNTHETIC_SILENT_GEAR_SHA,
                ChannelContractSignature.from(canonical).silentGearContractSha256());
        for (boolean cui : List.of(false, true)) {
            for (boolean voice : List.of(false, true)) {
                Registry observed = fixture(cui, voice);
                Atm10Normal81ClientContractEvidence.Match match = match(observed).orElseThrow();
                assertEquals(SYNTHETIC_FULL_SHA, match.normalizedFullClientContractSha256());
                assertEquals(cui, match.worldEditCuiPresent());
                assertEquals(voice, match.simpleVoiceChatPresent());
                assertEquals(voice ? ReviewedSimpleVoiceChatExtension.channels() : List.of(),
                        match.externallyOwnedPlayChannels());
                assertTrue(match.id().contains(cui ? "-worldedit-cui" : "-without-worldedit-cui"));
                assertTrue(match.id().contains(voice ? "-simple-voice-chat" : "-without-simple-voice-chat"));
                if (!cui || !voice) {
                    assertNotEquals(SYNTHETIC_FULL_SHA,
                            ChannelContractSignature.from(observed).fullContractSha256());
                }
            }
        }
    }

    @Test
    void productionIdentityNeverAcceptsSyntheticFixtureOrDigestSpoofing() {
        Registry observed = fixture(false, false);
        assertTrue(Atm10Normal81ClientContractEvidence.match(
                767, observed, ChannelContractSignature.from(observed)).isEmpty());
        assertTrue(Atm10Normal81ClientContractEvidence.match(
                767, observed, new ChannelContractSignature.Signatures(
                        Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                        SilentGearProtocol.ATM10_NORMAL_4_2.canonicalContractSha256())).isEmpty());
        assertTrue(Atm10Normal81ClientContractEvidence.matchExactContract(
                767, observed, ChannelContractSignature.from(fixture(true, true)),
                SYNTHETIC_FULL_SHA, SYNTHETIC_SILENT_GEAR_SHA).isEmpty());
    }

    @Test
    void wrongMinecraftAndSilentGearProtocolsNeverNormalize() {
        Registry observed = fixture(false, false);
        for (int protocol : List.of(5, 766, 768)) {
            assertTrue(Atm10Normal81ClientContractEvidence.matchExactContract(
                    protocol, observed, ChannelContractSignature.from(observed),
                    SYNTHETIC_FULL_SHA, SYNTHETIC_SILENT_GEAR_SHA).isEmpty());
        }
        assertTrue(Atm10Normal81ClientContractEvidence.matchExactContract(
                767, observed, ChannelContractSignature.from(observed),
                SYNTHETIC_FULL_SHA, "0".repeat(64)).isEmpty());
    }

    @Test
    void partialVoiceChatIsRejectedForEveryMissingChannel() {
        for (Channel missing : ReviewedSimpleVoiceChatExtension.channels()) {
            assertTrue(match(editPlay(fixture(true, true), channels -> {
                channels.remove(missing);
                return channels;
            })).isEmpty(), missing.id());
        }
    }

    @Test
    void voiceChatShapeChangesExtraChannelsAndDuplicatesAreRejected() {
        Channel original = ReviewedSimpleVoiceChatExtension.channels().getFirst();
        for (Channel changed : List.of(
                new Channel(original.id(), "other", original.flow(), original.optional()),
                new Channel(original.id(), original.version(), Flow.BIDIRECTIONAL, original.optional()),
                new Channel(original.id(), original.version(), original.flow(), false),
                new Channel("voicechat:unknown", original.version(), original.flow(), true))) {
            assertTrue(match(editPlay(fixture(true, true), channels -> {
                channels.set(channels.indexOf(original), changed);
                return channels;
            })).isEmpty());
        }
        assertTrue(match(append(fixture(true, true), PLAY, original)).isEmpty());
        assertTrue(match(append(fixture(false, false), PLAY, original)).isEmpty());
        assertTrue(match(append(fixture(true, true), CONFIGURATION, original)).isEmpty());
        assertTrue(match(append(fixture(false, false), CONFIGURATION, original)).isEmpty());
    }

    @Test
    void malformedCuiIsNotTreatedAsAbsent() {
        for (Channel changed : List.of(
                new Channel(CUI.id(), "2", CUI.flow(), true),
                new Channel(CUI.id(), "1", Flow.CLIENTBOUND, true),
                new Channel(CUI.id(), "1", CUI.flow(), false))) {
            assertTrue(match(append(fixture(false, true), PLAY, changed)).isEmpty());
        }
        assertTrue(match(append(fixture(true, true), PLAY, CUI)).isEmpty());
        assertTrue(match(append(fixture(false, true), CONFIGURATION, CUI)).isEmpty());
        assertTrue(match(append(fixture(true, true), CONFIGURATION, CUI)).isEmpty());
    }

    @Test
    void anyOtherStructuralDifferenceStillFailsTheCompleteDigest() {
        Registry observed = fixture(false, false);
        assertTrue(match(append(observed, PLAY,
                new Channel("newmod:structural", "1", Flow.CLIENTBOUND, false))).isEmpty());
        assertTrue(match(append(observed, CONFIGURATION,
                new Channel("game:new_configuration", "2", Flow.BIDIRECTIONAL, false))).isEmpty());
        assertTrue(match(editPlay(observed, channels -> {
            channels.set(0, new Channel("game:state", "3", Flow.CLIENTBOUND, false));
            return channels;
        })).isEmpty());
        assertTrue(match(editPlay(observed, channels -> {
            channels.remove(0);
            return channels;
        })).isEmpty());
    }

    @Test
    void malformedOrDuplicateIgnoredExtensionsCannotBypassStrictShapeChecks() {
        for (String id : List.of("voicechat:", "voicechat:state", "worldedit:cui")) {
            for (IgnoredChannelReason reason : IgnoredChannelReason.values()) {
                IgnoredChannel ignored = new IgnoredChannel(
                        PLAY, id, "1", Flow.CLIENTBOUND, true, reason);
                for (boolean present : List.of(false, true)) {
                    assertTrue(match(withIgnored(fixture(present, present), ignored)).isEmpty());
                }
            }
        }
    }

    @Test
    void ignoredStructuralDuplicatesCannotHideChangedVersionOrFlow() {
        Registry observed = fixture(false, false);
        for (IgnoredChannel duplicate : List.of(
                new IgnoredChannel(PLAY, "game:state", "changed", Flow.CLIENTBOUND, false,
                        IgnoredChannelReason.DUPLICATE_CHANNEL_ID),
                new IgnoredChannel(PLAY, "game:state", "2", Flow.SERVERBOUND, false,
                        IgnoredChannelReason.DUPLICATE_CHANNEL_ID),
                new IgnoredChannel(CONFIGURATION, "game:configuration", "changed", Flow.CLIENTBOUND,
                        false, IgnoredChannelReason.DUPLICATE_CHANNEL_ID))) {
            Registry withDuplicate = withIgnored(observed, duplicate);
            assertEquals(ChannelContractSignature.from(observed),
                    ChannelContractSignature.from(withDuplicate));
            assertTrue(match(withDuplicate).isEmpty());
        }
    }

    @Test
    void unrelatedInvalidComponentsRemainRetainedWithoutBecomingAnAdmissionGate() {
        Registry observed = fixture(false, false);
        List<IgnoredChannel> invalid = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            invalid.add(new IgnoredChannel(PLAY, "invalid" + index + ":", "1", Flow.CLIENTBOUND,
                    false, IgnoredChannelReason.INVALID_RESOURCE_LOCATION));
        }
        Registry withInvalid = new Registry(observed.protocols(),
                observed.channelCount() + invalid.size(), invalid.size(), invalid);
        assertTrue(match(withInvalid).isPresent());
        assertEquals(invalid, withInvalid.ignoredChannels());
        assertEquals(5, withInvalid.ignoredChannelCount());
    }

    @Test
    void comparisonPreservesOriginalAdvertisementAndIgnoredEvidence() {
        IgnoredChannel ignored = new IgnoredChannel(
                PLAY, "ae2:", "ae2", Flow.CLIENTBOUND, false,
                IgnoredChannelReason.INVALID_RESOURCE_LOCATION);
        Registry observed = withIgnored(fixture(false, false), ignored);
        Map<Integer, List<Channel>> originalProtocols = Map.copyOf(observed.protocols());
        int originalDeclaredCount = observed.declaredChannelCount();
        ChannelContractSignature.Signatures originalSignatures = ChannelContractSignature.from(observed);
        Atm10Normal81ClientContractEvidence.Match match = match(observed).orElseThrow();
        assertEquals(List.of(), match.externallyOwnedPlayChannels());
        assertEquals(originalProtocols, observed.protocols());
        assertEquals(originalDeclaredCount, observed.declaredChannelCount());
        assertEquals(List.of(ignored), observed.ignoredChannels());
        assertEquals(1, observed.ignoredChannelCount());
        assertEquals(originalSignatures, ChannelContractSignature.from(observed));
        assertFalse(observed.channelIds().contains(CUI.id()));
        assertFalse(observed.channelIds().contains("voicechat:secret"));
    }

    @Test
    void orderIsIrrelevantAndExternallyOwnedListIsImmutable() {
        Registry reversed = editPlay(fixture(true, true), channels -> {
            Collections.reverse(channels);
            return channels;
        });
        Atm10Normal81ClientContractEvidence.Match match = match(reversed).orElseThrow();
        assertEquals(14, match.externallyOwnedPlayChannels().size());
        assertTrue(ReviewedSimpleVoiceChatExtension.channels().containsAll(match.externallyOwnedPlayChannels()));
        assertThrows(UnsupportedOperationException.class,
                () -> match.externallyOwnedPlayChannels().clear());
    }

    private static Optional<Atm10Normal81ClientContractEvidence.Match> match(Registry registry) {
        return Atm10Normal81ClientContractEvidence.matchExactContract(
                767, registry, ChannelContractSignature.from(registry),
                SYNTHETIC_FULL_SHA, SYNTHETIC_SILENT_GEAR_SHA);
    }

    private static Registry fixture(boolean cui, boolean voice) {
        List<Channel> play = new ArrayList<>(List.of(
                new Channel("game:state", "2", Flow.CLIENTBOUND, false),
                new Channel("silentgear:synthetic", "4.2", Flow.CLIENTBOUND, false)));
        if (cui) {
            play.add(CUI);
        }
        if (voice) {
            play.addAll(ReviewedSimpleVoiceChatExtension.channels());
        }
        return new Registry(Map.of(
                PLAY, play,
                CONFIGURATION, List.of(new Channel("game:configuration", "2", Flow.BIDIRECTIONAL, false))));
    }

    private static Registry append(Registry registry, int protocol, Channel channel) {
        Map<Integer, List<Channel>> protocols = new LinkedHashMap<>(registry.protocols());
        List<Channel> channels = new ArrayList<>(registry.channelsFor(protocol));
        channels.add(channel);
        protocols.put(protocol, channels);
        return new Registry(protocols);
    }

    private static Registry editPlay(Registry registry, UnaryOperator<List<Channel>> edit) {
        Map<Integer, List<Channel>> protocols = new LinkedHashMap<>(registry.protocols());
        protocols.put(PLAY, edit.apply(new ArrayList<>(registry.channelsFor(PLAY))));
        return new Registry(protocols);
    }

    private static Registry withIgnored(Registry registry, IgnoredChannel ignored) {
        return new Registry(registry.protocols(), registry.channelCount() + 1, 1, List.of(ignored));
    }
}
