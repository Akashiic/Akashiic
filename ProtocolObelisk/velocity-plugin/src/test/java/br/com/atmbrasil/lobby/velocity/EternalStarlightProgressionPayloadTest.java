package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EternalStarlightProgressionPayloadTest {
    @Test
    void acceptsEmptyAndBoundedUniqueResourceLocationSets() {
        assertTrue(EternalStarlightProgressionPayload.isValid(encode(List.of())));
        assertTrue(EternalStarlightProgressionPayload.isValid(encode(List.of(
                "eternal_starlight:the_gatekeeper",
                "minecraft:zombie"))));

        List<String> maximum = new ArrayList<>();
        for (int index = 0;
                index < EternalStarlightProgressionPayload.MAXIMUM_ENTRIES;
                index++) {
            maximum.add("eternal_starlight:entity_" + index);
        }
        assertTrue(EternalStarlightProgressionPayload.isValid(encode(maximum)));
    }

    @Test
    void rejectsInvalidCountsTruncationAndTrailingBytes() {
        assertFalse(EternalStarlightProgressionPayload.isValid(null));
        assertFalse(EternalStarlightProgressionPayload.isValid(new byte[3]));
        assertFalse(EternalStarlightProgressionPayload.isValid(
                ByteBuffer.allocate(4).putInt(-1).array()));
        assertFalse(EternalStarlightProgressionPayload.isValid(ByteBuffer.allocate(4)
                .putInt(EternalStarlightProgressionPayload.MAXIMUM_ENTRIES + 1)
                .array()));
        assertFalse(EternalStarlightProgressionPayload.isValid(
                new byte[] {0, 0, 0, 1}));
        assertFalse(EternalStarlightProgressionPayload.isValid(
                new byte[] {0, 0, 0, 0, 1}));
    }

    @Test
    void rejectsNonCanonicalLengthsInvalidUtf8AndInvalidIdentifiers() {
        assertFalse(EternalStarlightProgressionPayload.isValid(new byte[] {
            0, 0, 0, 1, (byte) 0x81, 0, 'a'
        }));
        assertFalse(EternalStarlightProgressionPayload.isValid(new byte[] {
            0, 0, 0, 1, 2, (byte) 0xc3, 0x28
        }));
        assertFalse(EternalStarlightProgressionPayload.isValid(
                encode(List.of("missing_namespace"))));
        assertFalse(EternalStarlightProgressionPayload.isValid(
                encode(List.of("Minecraft:zombie"))));
        assertFalse(EternalStarlightProgressionPayload.isValid(encode(List.of(
                "minecraft:zombie", "minecraft:zombie"))));
    }

    @Test
    void rejectsStringsAboveTheAuditedByteBound() {
        String oversized = "example:" + "a".repeat(
                EternalStarlightProgressionPayload.MAXIMUM_RESOURCE_LOCATION_UTF8_BYTES);
        assertFalse(EternalStarlightProgressionPayload.isValid(encode(List.of(oversized))));
    }

    private static byte[] encode(List<String> entries) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(4).putInt(entries.size()).array());
        for (String entry : entries) {
            byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
            writeVarInt(output, bytes.length);
            output.writeBytes(bytes);
        }
        return output.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            output.write((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        output.write(remaining);
    }
}
