package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Independent, bounded wire audit for the reviewed ATM10 8.0 CONFIG extensions. */
final class Atm10Normal80DynamicRegistryWireTest {
    private static final int MAXIMUM_NBT_DEPTH = 64;
    private static final int MAXIMUM_NBT_COLLECTION_ELEMENTS = 262_144;
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final List<RegistryEntry> OMITTED_VANILLA_ENTRIES = List.of(
            new RegistryEntry("minecraft:dimension_type", "minecraft:the_end"),
            new RegistryEntry("minecraft:enchantment", "minecraft:channeling"),
            new RegistryEntry("minecraft:enchantment", "minecraft:protection"),
            new RegistryEntry("minecraft:enchantment", "minecraft:sharpness"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:ashen"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:black"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:chestnut"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:pale"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:rusty"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:snowy"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:spotted"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:striped"),
            new RegistryEntry("minecraft:wolf_variant", "minecraft:woods"),
            new RegistryEntry("minecraft:worldgen/biome", "minecraft:end_barrens"),
            new RegistryEntry("minecraft:worldgen/biome", "minecraft:end_highlands"),
            new RegistryEntry("minecraft:worldgen/biome", "minecraft:end_midlands"),
            new RegistryEntry("minecraft:worldgen/biome", "minecraft:small_end_islands"),
            new RegistryEntry("minecraft:worldgen/biome", "minecraft:the_end"));
    private static final List<RegistryEntry> RETAINED_MINECRAFT_NAMESPACE_EXTENSIONS = List.of(
            new RegistryEntry("computercraft:turtle_upgrade", "minecraft:crafting_table"),
            new RegistryEntry("computercraft:turtle_upgrade", "minecraft:diamond_axe"),
            new RegistryEntry("computercraft:turtle_upgrade", "minecraft:diamond_hoe"),
            new RegistryEntry("computercraft:turtle_upgrade", "minecraft:diamond_pickaxe"),
            new RegistryEntry("computercraft:turtle_upgrade", "minecraft:diamond_shovel"),
            new RegistryEntry("computercraft:turtle_upgrade", "minecraft:diamond_sword"),
            new RegistryEntry("minecraft:trim_material", "minecraft:allthemodium"),
            new RegistryEntry("minecraft:trim_material", "minecraft:demonite"),
            new RegistryEntry("minecraft:trim_material", "minecraft:unobtainium"),
            new RegistryEntry("minecraft:trim_material", "minecraft:vibranium"));

    @Test
    void reviewedPacketsDecodeCompletelyWithoutVanillaKnownPackOverlap()
            throws Exception {
        EmbeddedDynamicRegistryProfile profile =
                EmbeddedDynamicRegistryProfile.loadAtm10Normal80(
                        Atm10Normal80DynamicRegistryWireTest.class.getClassLoader());
        Map<String, Set<String>> entriesByRegistry = new HashMap<>();
        int decodedEntries = 0;

        for (RegistryShimPacket packet : profile.packets()) {
            ParsedRegistry parsed = parse(packet.packetBody());
            assertEquals(packet.registryId(), parsed.registryId());
            assertEquals(packet.entryCount(), parsed.entryIds().size());
            if (parsed.registryId().equals("minecraft:dimension_type")) {
                assertEquals("compactmachines:compact_world", parsed.firstEntryId());
            }
            assertTrue(entriesByRegistry.put(parsed.registryId(), parsed.entryIds()) == null);
            decodedEntries = Math.addExact(decodedEntries, parsed.entryIds().size());
        }

        assertEquals(50, entriesByRegistry.size());
        assertEquals(1_946, decodedEntries);
        for (RegistryEntry omitted : OMITTED_VANILLA_ENTRIES) {
            assertFalse(entriesByRegistry
                    .getOrDefault(omitted.registryId(), Set.of())
                    .contains(omitted.entryId()), omitted.toString());
        }
        for (RegistryEntry retained : RETAINED_MINECRAFT_NAMESPACE_EXTENSIONS) {
            assertTrue(entriesByRegistry
                    .getOrDefault(retained.registryId(), Set.of())
                    .contains(retained.entryId()), retained.toString());
        }
    }

    private static ParsedRegistry parse(byte[] packetBody) {
        Cursor cursor = new Cursor(packetBody);
        String registryId = cursor.readResourceLocation();
        int entryCount = cursor.readBoundedVarInt(1, 65_535, "entry count");
        LinkedHashSet<String> entryIds = new LinkedHashSet<>(entryCount);
        String firstEntryId = null;
        for (int index = 0; index < entryCount; index++) {
            String entryId = cursor.readResourceLocation();
            if (index == 0) {
                firstEntryId = entryId;
            }
            if (!entryIds.add(entryId)) {
                throw new IllegalArgumentException(
                        "duplicate dynamic registry entry " + registryId + " / " + entryId);
            }
            int optionalMarker = cursor.readUnsignedByte();
            if (optionalMarker != 1) {
                throw new IllegalArgumentException(
                        "dynamic registry entry has absent or invalid NBT "
                                + registryId + " / " + entryId);
            }
            int rootType = cursor.readUnsignedByte();
            if (rootType != 10) {
                throw new IllegalArgumentException(
                        "dynamic registry entry NBT root is not a compound "
                                + registryId + " / " + entryId);
            }
            skipNbtPayload(cursor, rootType, 0);
        }
        if (cursor.remaining() != 0) {
            throw new IllegalArgumentException(
                    "dynamic registry packet has trailing bytes: " + registryId);
        }
        return new ParsedRegistry(registryId, firstEntryId, Set.copyOf(entryIds));
    }

    private static void skipNbtPayload(Cursor cursor, int tagType, int depth) {
        if (depth > MAXIMUM_NBT_DEPTH) {
            throw new IllegalArgumentException("NBT depth exceeds audit bound");
        }
        switch (tagType) {
            case 0 -> {
                return;
            }
            case 1 -> cursor.skip(1);
            case 2 -> cursor.skip(2);
            case 3, 5 -> cursor.skip(4);
            case 4, 6 -> cursor.skip(8);
            case 7 -> cursor.skip(cursor.readBoundedLength(1));
            case 8 -> cursor.skipNbtString();
            case 9 -> {
                int elementType = cursor.readUnsignedByte();
                if (elementType > 12) {
                    throw new IllegalArgumentException("invalid NBT list element type");
                }
                int elementCount = cursor.readBoundedCollectionLength();
                if (elementType == 0 && elementCount != 0) {
                    throw new IllegalArgumentException("non-empty NBT end-tag list");
                }
                for (int index = 0; index < elementCount; index++) {
                    skipNbtPayload(cursor, elementType, depth + 1);
                }
            }
            case 10 -> {
                while (true) {
                    int childType = cursor.readUnsignedByte();
                    if (childType == 0) {
                        return;
                    }
                    if (childType > 12) {
                        throw new IllegalArgumentException("invalid NBT compound child type");
                    }
                    cursor.skipNbtString();
                    skipNbtPayload(cursor, childType, depth + 1);
                }
            }
            case 11 -> cursor.skip(cursor.readBoundedLength(4));
            case 12 -> cursor.skip(cursor.readBoundedLength(8));
            default -> throw new IllegalArgumentException("invalid NBT tag type " + tagType);
        }
    }

    private record ParsedRegistry(String registryId, String firstEntryId, Set<String> entryIds) {
        private ParsedRegistry {
            Objects.requireNonNull(registryId, "registryId");
            Objects.requireNonNull(firstEntryId, "firstEntryId");
            entryIds = Set.copyOf(Objects.requireNonNull(entryIds, "entryIds"));
        }
    }

    private record RegistryEntry(String registryId, String entryId) {
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int index;

        private Cursor(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        private String readResourceLocation() {
            int byteCount = readBoundedVarInt(1, 256, "resource-location byte count");
            byte[] encoded = readBytes(byteCount);
            final String value;
            try {
                value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(encoded))
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("invalid resource-location UTF-8", exception);
            }
            if (!RESOURCE_LOCATION.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid resource location " + value);
            }
            return value;
        }

        private int readBoundedVarInt(int minimum, int maximum, String label) {
            int value = readVarInt();
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(label + " is outside bounds");
            }
            return value;
        }

        private int readVarInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int current = readUnsignedByte();
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("oversized VarInt");
        }

        private int readBoundedCollectionLength() {
            int count = readInt();
            if (count < 0 || count > MAXIMUM_NBT_COLLECTION_ELEMENTS) {
                throw new IllegalArgumentException("NBT collection length is outside bounds");
            }
            return count;
        }

        private int readBoundedLength(int elementBytes) {
            int count = readBoundedCollectionLength();
            try {
                return Math.multiplyExact(count, elementBytes);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("NBT byte length overflow", exception);
            }
        }

        private void skipNbtString() {
            int byteCount = readUnsignedShort();
            skip(byteCount);
        }

        private int readUnsignedByte() {
            if (index >= bytes.length) {
                throw new IllegalArgumentException("truncated dynamic registry packet");
            }
            return bytes[index++] & 0xFF;
        }

        private int readUnsignedShort() {
            int high = readUnsignedByte();
            int low = readUnsignedByte();
            return (high << 8) | low;
        }

        private int readInt() {
            return (readUnsignedByte() << 24)
                    | (readUnsignedByte() << 16)
                    | (readUnsignedByte() << 8)
                    | readUnsignedByte();
        }

        private byte[] readBytes(int length) {
            requireAvailable(length);
            byte[] result = java.util.Arrays.copyOfRange(bytes, index, index + length);
            index += length;
            return result;
        }

        private void skip(int length) {
            requireAvailable(length);
            index += length;
        }

        private void requireAvailable(int length) {
            if (length < 0 || index > bytes.length - length) {
                throw new IllegalArgumentException("truncated dynamic registry packet");
            }
        }

        private int remaining() {
            return bytes.length - index;
        }
    }
}
