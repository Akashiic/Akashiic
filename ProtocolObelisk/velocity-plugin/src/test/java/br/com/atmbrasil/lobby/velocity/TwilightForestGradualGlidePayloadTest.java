package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TwilightForestGradualGlidePayloadTest {
    private static final UUID PLAYER =
            UUID.fromString("e1f69e79-0432-33bb-ad07-2077994de007");

    @Test
    void acceptsBothCanonicalStatesBoundToTheCurrentPlayer() {
        for (boolean gliding : new boolean[] {false, true}) {
            byte[] payload = payload(gliding, PLAYER);
            assertTrue(TwilightForestGradualGlidePayload.isStructurallyValid(payload));
            assertTrue(TwilightForestGradualGlidePayload.belongsTo(payload, PLAYER));
            assertTrue(ReviewedAtmCompatibility.matchesReviewedSessionPayload(
                    ReviewedAtmCompatibility.TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK.id(),
                    payload,
                    PLAYER));
        }
    }

    @Test
    void rejectsAnotherPlayerEvenWhenTheWireShapeIsValid() {
        byte[] payload = payload(true, UUID.fromString(
                "00000000-0000-0000-0000-000000000001"));
        assertTrue(TwilightForestGradualGlidePayload.isStructurallyValid(payload));
        assertFalse(TwilightForestGradualGlidePayload.belongsTo(payload, PLAYER));
        assertFalse(ReviewedAtmCompatibility.matchesReviewedSessionPayload(
                ReviewedAtmCompatibility.TWILIGHT_FOREST_GRADUAL_GLIDE_PLAY_SINK.id(),
                payload,
                PLAYER));
    }

    @Test
    void rejectsNonCanonicalBooleanTruncationAndTrailingBytes() {
        byte[] nonCanonicalBoolean = payload(false, PLAYER);
        nonCanonicalBoolean[0] = 2;
        assertFalse(TwilightForestGradualGlidePayload.isStructurallyValid(
                nonCanonicalBoolean));
        assertFalse(TwilightForestGradualGlidePayload.isStructurallyValid(new byte[16]));
        assertFalse(TwilightForestGradualGlidePayload.isStructurallyValid(new byte[18]));
        assertFalse(TwilightForestGradualGlidePayload.isStructurallyValid(null));
    }

    private static byte[] payload(boolean gliding, UUID player) {
        return ByteBuffer.allocate(TwilightForestGradualGlidePayload.PAYLOAD_BYTES)
                .put((byte) (gliding ? 1 : 0))
                .putLong(player.getMostSignificantBits())
                .putLong(player.getLeastSignificantBits())
                .array();
    }
}
