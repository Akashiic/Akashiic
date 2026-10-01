package br.com.atmbrasil.lobby.velocity.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.CompoundTag;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Entry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MinecraftRegistryPacketCodecSelectionTest {
    private static final int MAXIMUM_BYTES = 65_536;

    @Test
    void selectionKeepsChosenEntriesByteForByteInOriginalOrder() throws Exception {
        byte[] original = MinecraftRegistryPacketCodec.encode(
                "minecraft:worldgen/biome",
                List.of(
                        entry("minecraft:plains", "minecraft:plains"),
                        entry("testmod:glow", "testmod:glow"),
                        entry("minecraft:custom_peaks", "minecraft:custom_peaks"),
                        entry("testmod:dusk", "testmod:dusk")),
                MAXIMUM_BYTES);

        byte[] selected = MinecraftRegistryPacketCodec.selectEntries(
                original, id -> !id.equals("minecraft:plains"), MAXIMUM_BYTES).orElseThrow();

        byte[] expected = MinecraftRegistryPacketCodec.encode(
                "minecraft:worldgen/biome",
                List.of(
                        entry("testmod:glow", "testmod:glow"),
                        entry("minecraft:custom_peaks", "minecraft:custom_peaks"),
                        entry("testmod:dusk", "testmod:dusk")),
                MAXIMUM_BYTES);
        assertArrayEquals(expected, selected);
    }

    @Test
    void selectingNothingYieldsNoPacket() throws Exception {
        byte[] original = MinecraftRegistryPacketCodec.encode(
                "minecraft:damage_type",
                List.of(entry("minecraft:generic", "x")),
                MAXIMUM_BYTES);
        assertTrue(MinecraftRegistryPacketCodec.selectEntries(
                original, id -> false, MAXIMUM_BYTES).isEmpty());
    }

    @Test
    void selectionRejectsMalformedInput() {
        assertThrows(ProtocolViolationException.class, () -> MinecraftRegistryPacketCodec.selectEntries(
                new byte[] {0x01, 0x41}, id -> true, MAXIMUM_BYTES));
    }

    @Test
    void nbtStringsCoverValuesNestedValuesAndCompoundKeys() throws Exception {
        LinkedHashMap<String, MinecraftRegistryPacketCodec.NbtValue> nested = new LinkedHashMap<>();
        nested.put("testmod:keyed_biome", MinecraftRegistryPacketCodec.intTag(1));
        LinkedHashMap<String, MinecraftRegistryPacketCodec.NbtValue> fields = new LinkedHashMap<>();
        fields.put("biome", MinecraftRegistryPacketCodec.stringTag("testmod:glow"));
        fields.put("biomes", MinecraftRegistryPacketCodec.stringList("testmod:a", "#testmod:tag"));
        fields.put("weights", new CompoundTag(nested));
        byte[] packet = MinecraftRegistryPacketCodec.encode(
                "testmod:biome_data",
                List.of(new Entry("testmod:entry", new CompoundTag(fields))),
                MAXIMUM_BYTES);

        Set<String> strings = MinecraftRegistryPacketCodec.nbtStrings(packet, MAXIMUM_BYTES);
        assertTrue(strings.containsAll(Set.of(
                "testmod:glow", "testmod:a", "#testmod:tag", "testmod:keyed_biome", "biome")));
        assertEquals(false, strings.contains("testmod:entry"));
    }

    private static Entry entry(String id, String value) {
        LinkedHashMap<String, MinecraftRegistryPacketCodec.NbtValue> fields = new LinkedHashMap<>();
        fields.put("value", MinecraftRegistryPacketCodec.stringTag(value));
        return new Entry(id, new CompoundTag(fields));
    }
}
