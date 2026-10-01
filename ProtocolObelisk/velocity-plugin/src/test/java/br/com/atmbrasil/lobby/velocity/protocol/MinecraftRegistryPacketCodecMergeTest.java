package br.com.atmbrasil.lobby.velocity.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.CompoundTag;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Entry;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MinecraftRegistryPacketCodecMergeTest {
    private static final int MAXIMUM_BYTES = 65_536;

    @Test
    void mergePreservesOriginalEntriesAndAppendsReviewedExtension() throws Exception {
        byte[] original = MinecraftRegistryPacketCodec.encode(
                "minecraft:enchantment",
                List.of(entry("minecraft:protection", 10), entry("minecraft:sharpness", 20)),
                MAXIMUM_BYTES);
        byte[] extension = MinecraftRegistryPacketCodec.encode(
                "minecraft:enchantment",
                List.of(entry("example:first", 30), entry("example:second", 40)),
                MAXIMUM_BYTES);

        byte[] merged = MinecraftRegistryPacketCodec.mergeDistinctEntries(
                original, extension, MAXIMUM_BYTES);
        MinecraftRegistryPacketCodec.Inspection inspection =
                MinecraftRegistryPacketCodec.inspect(merged, MAXIMUM_BYTES);

        assertEquals("minecraft:enchantment", inspection.registryId());
        assertEquals(
                List.of(
                        "minecraft:protection",
                        "minecraft:sharpness",
                        "example:first",
                        "example:second"),
                inspection.entryIds());
        assertEquals(4, inspection.entriesWithData());

        // The merge changes only the entry-count VarInt and appends reviewed entry bytes. A
        // deterministic second merge from the same inputs must therefore be byte-identical.
        assertArrayEquals(
                merged,
                MinecraftRegistryPacketCodec.mergeDistinctEntries(
                        original, extension, MAXIMUM_BYTES));
    }

    @Test
    void registryIdPrefixCanBeRecognizedBeforeMalformedTrailingBody() throws Exception {
        byte[] valid = MinecraftRegistryPacketCodec.encode(
                "minecraft:enchantment",
                List.of(entry("minecraft:protection", 10)),
                MAXIMUM_BYTES);
        int registryIdOnlyBytes = 1 + "minecraft:enchantment".length();
        byte[] truncatedAfterRegistryId = java.util.Arrays.copyOf(
                valid, registryIdOnlyBytes);

        assertEquals(
                "minecraft:enchantment",
                MinecraftRegistryPacketCodec.inspectRegistryId(
                        truncatedAfterRegistryId, MAXIMUM_BYTES));
        assertThrows(
                ProtocolViolationException.class,
                () -> MinecraftRegistryPacketCodec.inspect(
                        truncatedAfterRegistryId, MAXIMUM_BYTES));
    }

    @Test
    void duplicateEntryNeverOverridesPaperData() throws Exception {
        byte[] original = MinecraftRegistryPacketCodec.encode(
                "minecraft:enchantment",
                List.of(entry("minecraft:protection", 10)),
                MAXIMUM_BYTES);
        byte[] extension = MinecraftRegistryPacketCodec.encode(
                "minecraft:enchantment",
                List.of(entry("minecraft:protection", 99)),
                MAXIMUM_BYTES);

        assertThrows(
                ProtocolViolationException.class,
                () -> MinecraftRegistryPacketCodec.mergeDistinctEntries(
                        original, extension, MAXIMUM_BYTES));
    }

    @Test
    void differentRegistryIdsAreRejected() throws Exception {
        byte[] original = MinecraftRegistryPacketCodec.encode(
                "minecraft:enchantment",
                List.of(entry("minecraft:protection", 10)),
                MAXIMUM_BYTES);
        byte[] extension = MinecraftRegistryPacketCodec.encode(
                "example:other_registry",
                List.of(entry("example:value", 20)),
                MAXIMUM_BYTES);

        assertThrows(
                ProtocolViolationException.class,
                () -> MinecraftRegistryPacketCodec.mergeDistinctEntries(
                        original, extension, MAXIMUM_BYTES));
    }

    @Test
    void mergedPacketHonorsConfiguredByteBound() throws Exception {
        byte[] original = MinecraftRegistryPacketCodec.encode(
                "minecraft:enchantment",
                List.of(entry("minecraft:protection", 10)),
                MAXIMUM_BYTES);
        byte[] extension = MinecraftRegistryPacketCodec.encode(
                "minecraft:enchantment",
                List.of(entry("example:first", 20)),
                MAXIMUM_BYTES);

        int impossibleBound = Math.max(original.length, extension.length);
        assertThrows(
                ProtocolViolationException.class,
                () -> MinecraftRegistryPacketCodec.mergeDistinctEntries(
                        original, extension, impossibleBound));
    }

    private static Entry entry(String id, int marker) {
        LinkedHashMap<String, MinecraftRegistryPacketCodec.NbtValue> fields =
                new LinkedHashMap<>();
        fields.put("marker", MinecraftRegistryPacketCodec.intTag(marker));
        fields.put("description", new CompoundTag(new LinkedHashMap<>()));
        return new Entry(id, new CompoundTag(fields));
    }
}
