package br.com.atmbrasil.lobby.velocity.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MinecraftRegistryPacketCodecInspectionTest {
    @Test
    void inspectorReturnsOrderedUniqueEntryIdsAndDataCount() throws Exception {
        byte[] packet = packet(
                "minecraft:enchantment",
                List.of("evilcraft:vengeance", "undergarden:ricochet"),
                false);

        MinecraftRegistryPacketCodec.Inspection inspection =
                MinecraftRegistryPacketCodec.inspect(packet, packet.length);

        assertEquals("minecraft:enchantment", inspection.registryId());
        assertEquals(
                List.of("evilcraft:vengeance", "undergarden:ricochet"),
                inspection.entryIds());
        assertEquals(0, inspection.entriesWithData());
        assertEquals(packet.length, inspection.packetBytes());
    }

    @Test
    void duplicateEntriesTrailingBytesAndNonCanonicalVarIntsAreRejected()
            throws Exception {
        byte[] duplicate = packet(
                "minecraft:enchantment",
                List.of("evilcraft:vengeance", "evilcraft:vengeance"),
                false);
        assertThrows(ProtocolViolationException.class, () ->
                MinecraftRegistryPacketCodec.inspect(duplicate, duplicate.length));

        byte[] valid = packet(
                "minecraft:enchantment", List.of("evilcraft:vengeance"), false);
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(ProtocolViolationException.class, () ->
                MinecraftRegistryPacketCodec.inspect(trailing, trailing.length));

        byte[] nonCanonicalRegistryLength = new byte[valid.length + 1];
        nonCanonicalRegistryLength[0] = (byte) (valid[0] | 0x80);
        nonCanonicalRegistryLength[1] = 0;
        System.arraycopy(valid, 1, nonCanonicalRegistryLength, 2, valid.length - 1);
        assertThrows(ProtocolViolationException.class, () ->
                MinecraftRegistryPacketCodec.inspect(
                        nonCanonicalRegistryLength, nonCanonicalRegistryLength.length));
    }

    @Test
    void inspectorEnforcesItsConfiguredPacketBudget() throws Exception {
        byte[] packet = packet(
                "minecraft:enchantment", List.of("evilcraft:vengeance"), false);
        assertThrows(ProtocolViolationException.class, () ->
                MinecraftRegistryPacketCodec.inspect(packet, packet.length - 1));
        assertThrows(IllegalArgumentException.class, () ->
                MinecraftRegistryPacketCodec.inspect(packet, 1_048_577));
    }

    private static byte[] packet(
            String registryId, List<String> entryIds, boolean withEmptyCompound)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeMinecraftString(output, registryId);
            writeVarInt(output, entryIds.size());
            for (String entryId : entryIds) {
                writeMinecraftString(output, entryId);
                output.writeBoolean(withEmptyCompound);
                if (withEmptyCompound) {
                    output.writeByte(10);
                    output.writeByte(0);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void writeMinecraftString(DataOutputStream output, String value)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, encoded.length);
        output.write(encoded);
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }
}
