package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EnderDrivesTypeCountRequestPayloadTest {
    @Test
    void acceptsTheExactStringAndTwoSignedVarIntCodec() {
        assertTrue(EnderDrivesTypeCountRequestPayload.isValid(payload("global", 0, 63)));
        assertTrue(EnderDrivesTypeCountRequestPayload.isValid(payload(
                "player_e1f69e79-0432-33bb-ad07-2077994de007",
                Integer.MAX_VALUE,
                Integer.MIN_VALUE)));
        assertTrue(EnderDrivesTypeCountRequestPayload.isValid(payload("", -1, 0)));

        String maximumThreeByteScope = "\u0800".repeat(
                EnderDrivesTypeCountRequestPayload.MAXIMUM_SCOPE_PREFIX_CHARACTERS);
        byte[] maximumPayload = payload(maximumThreeByteScope, -1, -1);
        assertEquals(
                EnderDrivesTypeCountRequestPayload.MAXIMUM_PAYLOAD_BYTES,
                maximumPayload.length);
        assertTrue(EnderDrivesTypeCountRequestPayload.isValid(maximumPayload));
    }

    @Test
    void rejectsNullAndPayloadsOutsideTheAuditedWireBounds() {
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(null));
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(new byte[0]));
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(new byte[2]));
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(
                new byte[EnderDrivesTypeCountRequestPayload.MAXIMUM_PAYLOAD_BYTES + 1]));
        assertFalse(EnderDrivesTypeCountRequestPayload.hasValidSize(-1));
        assertTrue(EnderDrivesTypeCountRequestPayload.hasValidSize(
                EnderDrivesTypeCountRequestPayload.MINIMUM_PAYLOAD_BYTES));
        assertTrue(EnderDrivesTypeCountRequestPayload.hasValidSize(
                EnderDrivesTypeCountRequestPayload.MAXIMUM_PAYLOAD_BYTES));
    }

    @Test
    void rejectsInvalidUtf8LengthTruncationAndCharacterOverflow() {
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(
                new byte[] {2, (byte) 0xc3, 0x28, 0, 0}));
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(
                concat(varInt(5), new byte[] {'a', 'b', 'c', 0, 0})));
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(
                concat(varInt(-1), new byte[] {0, 0})));

        String tooManyCharacters = "a".repeat(
                EnderDrivesTypeCountRequestPayload.MAXIMUM_SCOPE_PREFIX_CHARACTERS + 1);
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(
                payload(tooManyCharacters, 0, 0)));

        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(concat(
                varInt(EnderDrivesTypeCountRequestPayload.MAXIMUM_SCOPE_PREFIX_UTF8_BYTES + 1),
                new byte[] {0, 0})));
    }

    @Test
    void rejectsNonCanonicalOverflowingAndTruncatedVarInts() {
        List<byte[]> malformed = List.of(
                new byte[] {(byte) 0x80, 0, 0, 0},
                new byte[] {0, (byte) 0x80, 0, 0},
                new byte[] {0, 0, (byte) 0x80, 0},
                new byte[] {
                    0,
                    (byte) 0xff,
                    (byte) 0xff,
                    (byte) 0xff,
                    (byte) 0xff,
                    0x7f,
                    0
                },
                new byte[] {
                    0,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    (byte) 0x80,
                    0
                });
        malformed.forEach(payload ->
                assertFalse(EnderDrivesTypeCountRequestPayload.isValid(payload)));
    }

    @Test
    void rejectsEveryTrailingByteAndAppliesTheCodecToBothReviewedRequests() {
        byte[] exact = payload("team_42", 17, 63);
        byte[] trailing = concat(exact, new byte[] {0});
        assertFalse(EnderDrivesTypeCountRequestPayload.isValid(trailing));

        for (PinnedPlayChannel request : List.of(
                ReviewedAtmCompatibility.ENDER_DRIVES_DISK_TYPE_COUNT_REQUEST_PLAY_SINK,
                ReviewedAtmCompatibility.ENDER_DRIVES_FLUID_DISK_TYPE_COUNT_REQUEST_PLAY_SINK)) {
            assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(request.id(), exact));
            assertFalse(ReviewedAtmCompatibility.matchesReviewedPayload(
                    request.id(), trailing));
        }
        assertTrue(ReviewedAtmCompatibility.matchesReviewedPayload(
                "example:unreviewed", trailing));
    }

    private static byte[] payload(String scopePrefix, int frequency, int typeLimit) {
        byte[] encodedScope = scopePrefix.getBytes(StandardCharsets.UTF_8);
        return concat(varInt(encodedScope.length), encodedScope, varInt(frequency),
                varInt(typeLimit));
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(5);
        int remaining = value;
        do {
            int current = remaining & 0x7f;
            remaining >>>= 7;
            if (remaining != 0) {
                current |= 0x80;
            }
            output.write(current);
        } while (remaining != 0);
        return output.toByteArray();
    }

    private static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            output.writeBytes(chunk);
        }
        return output.toByteArray();
    }
}
