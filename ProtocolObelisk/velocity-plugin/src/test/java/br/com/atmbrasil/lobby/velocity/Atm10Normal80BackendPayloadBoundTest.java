package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class Atm10Normal80BackendPayloadBoundTest {
    private static final String FROZEN_REGISTRY_CHANNEL = "neoforge:frozen_registry";

    @Test
    void velocityDiagnosticSizeMatchesTheReviewedBlockRegistryExactly() throws Exception {
        SilentGearEmbeddedProfile profile = SilentGearEmbeddedProfile.loadAtm10Normal80(
                getClass().getClassLoader(), 767, 1_048_576, 3_145_728);
        NeoForgeFrozenRegistryProfile.RegistryPayload blocks = registry(
                profile, "minecraft:block");

        assertEquals(1_979_483, blocks.byteLength());
        assertEquals(25, encodedStringBytes(FROZEN_REGISTRY_CHANNEL));
        assertEquals(1_979_508,
                blocks.byteLength() + encodedStringBytes(FROZEN_REGISTRY_CHANNEL));
    }

    @Test
    void reviewedClientboundLimitCoversTheLargestRegistryRatherThanOnlyFirstFailure()
            throws Exception {
        SilentGearEmbeddedProfile profile = SilentGearEmbeddedProfile.loadAtm10Normal80(
                getClass().getClassLoader(), 767, 1_048_576, 3_145_728);

        assertEquals(2_619_214, registry(profile, "minecraft:item").byteLength());
        assertEquals(2_619_214, profile.frozenRegistries().maximumPayloadBytes());
    }

    @Test
    void reviewedPlayFrameRaisesOnlyTheClientboundPropertyToTheObservedBound() {
        assertEquals(
                "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                ReviewedBackendPluginMessageBounds.ATM10_NORMAL_8_0_PROFILE_ID);
        assertEquals(
                "cb2ac2d901f09e6ed8abc6348f0431609de85aa23ab9dda1c6a88fa116ffb4be",
                ReviewedBackendPluginMessageBounds.OBSERVED_VELOCITY_LOG_SHA256);
        assertEquals(
                "com.velocitypowered.proxy.protocol.packet.PluginMessagePacket",
                ReviewedBackendPluginMessageBounds.OBSERVED_PACKET_CLASS);
        assertEquals(
                8_388_602,
                ReviewedBackendPluginMessageBounds
                        .OBSERVED_ATM10_NORMAL_8_0_PLAY_FRAME_BYTES);
        assertEquals(
                8_191_991,
                ReviewedBackendPluginMessageBounds.MINIMUM_PROPERTY_FOR_OBSERVED_FRAME_BYTES);
        assertEquals(
                5_347_218,
                ReviewedBackendPluginMessageBounds.PREVIOUS_CLIENTBOUND_PROPERTY_BYTES);
        assertEquals(
                8_388_602,
                ReviewedBackendPluginMessageBounds.REVIEWED_CLIENTBOUND_PROPERTY_BYTES);

        assertEquals(
                5_543_829L,
                ReviewedBackendPluginMessageBounds.decoderFrameCapacityBytes(
                        ReviewedBackendPluginMessageBounds
                                .PREVIOUS_CLIENTBOUND_PROPERTY_BYTES));
        assertTrue(ReviewedBackendPluginMessageBounds.decoderCanAcceptFrame(
                ReviewedBackendPluginMessageBounds.PREVIOUS_CLIENTBOUND_PROPERTY_BYTES,
                ReviewedBackendPluginMessageBounds.PREVIOUS_CLIENTBOUND_PROPERTY_BYTES));
        assertFalse(ReviewedBackendPluginMessageBounds.decoderCanAcceptFrame(
                ReviewedBackendPluginMessageBounds.PREVIOUS_CLIENTBOUND_PROPERTY_BYTES,
                ReviewedBackendPluginMessageBounds
                        .OBSERVED_ATM10_NORMAL_8_0_PLAY_FRAME_BYTES));

        assertEquals(
                8_388_602,
                ReviewedBackendPluginMessageBounds.requiredClientboundPropertyBytes(2_619_214));
        assertEquals(
                8_585_213L,
                ReviewedBackendPluginMessageBounds.decoderFrameCapacityBytes(8_388_602));
        assertTrue(ReviewedBackendPluginMessageBounds.decoderCanAcceptFrame(
                8_388_602, 8_388_602));
    }

    @Test
    void reviewedPlayFrameBoundRejectsInvalidInputsAndNeverShrinksFutureEvidence() {
        assertEquals(
                9_000_000,
                ReviewedBackendPluginMessageBounds.requiredClientboundPropertyBytes(9_000_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewedBackendPluginMessageBounds
                        .requiredClientboundPropertyBytes(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewedBackendPluginMessageBounds.decoderFrameCapacityBytes(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewedBackendPluginMessageBounds.decoderCanAcceptFrame(1, -1));
    }

    private static NeoForgeFrozenRegistryProfile.RegistryPayload registry(
            SilentGearEmbeddedProfile profile, String registryName) {
        return profile.frozenRegistries().registryPayloads().stream()
                .filter(payload -> payload.registryName().equals(registryName))
                .findFirst()
                .orElseThrow();
    }

    private static int encodedStringBytes(String value) {
        int utf8Bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return varIntBytes(utf8Bytes) + utf8Bytes;
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
