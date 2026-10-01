package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ForbiddenArcanusItemModifierRegistryTest {
    private static final List<String> EXPECTED_IDS = List.of(
            "forbidden_arcanus:aquatic",
            "forbidden_arcanus:demolishing",
            "forbidden_arcanus:eternal",
            "forbidden_arcanus:fiery",
            "forbidden_arcanus:magnetized",
            "forbidden_arcanus:soulbound");

    @Test
    void exact261RegistryContainsAllSixReviewedDefinitions() throws Exception {
        DecodedRegistry registry = decode(
                ForbiddenArcanusItemModifierRegistry.packetBodyForTest());

        assertEquals("forbidden_arcanus:item_modifier", registry.registryId());
        assertEquals(EXPECTED_IDS, registry.entries().stream()
                .map(DecodedEntry::id)
                .toList());

        CompoundNode magnetized = registry.entries().get(4).data();
        assertEquals(
                new StringNode("#minecraft:foot_armor"),
                compound(magnetized, "predicate").values().get("items"));
        CompoundNode magnetizedColors = compound(
                compound(magnetized, "display"), "tooltip_color");
        assertEquals(new IntNode(-3_618_345), magnetizedColors.values().get("start"));
        assertEquals(new IntNode(-11_048_605), magnetizedColors.values().get("end"));

        CompoundNode eternal = registry.entries().get(2).data();
        assertEquals(
                new ListNode(8, List.of(
                        new StringNode("minecraft:damage"),
                        new StringNode("minecraft:max_damage"))),
                eternal.values().get("components_to_remove"));
        assertEquals(2_747, ForbiddenArcanusItemModifierRegistry.packetBodyForTest().length);
        assertEquals(
                "bd73a8eb99de591e7bbcf2a16ffef24e69d17f30a6bdcbd691b4dc02c2d26db3",
                ForbiddenArcanusItemModifierRegistry.packetSha256ForTest());
    }

    @Test
    void packetAndCatalogKeepDefensiveOwnershipAndEnforceBudget() {
        RegistryShimPacket packet = ForbiddenArcanusItemModifierRegistry.packet(65_536);
        byte[] first = packet.packetBody();
        byte[] second = packet.packetBody();
        assertNotSame(first, second);
        assertArrayEquals(first, second);
        first[0] ^= 0x7F;
        assertArrayEquals(second, packet.packetBody());
        int belowExactPacketSize = second.length - 1;
        assertThrows(IllegalArgumentException.class, () ->
                ForbiddenArcanusItemModifierRegistry.packet(belowExactPacketSize));
    }

    @Test
    void catalogAcceptsOnlyTheReviewedVersionedShim() {
        List<RegistryShimPacket> resolved = RegistryShimCatalog.resolve(
                List.of("forbidden-arcanus-2.6.1"), 65_536);
        assertEquals(1, resolved.size());
        assertEquals(6, resolved.getFirst().entryCount());
        assertThrows(IllegalArgumentException.class, () ->
                RegistryShimCatalog.resolve(List.of("arbitrary-payload"), 65_536));
    }

    private static CompoundNode compound(CompoundNode parent, String key) {
        Node value = parent.values().get(key);
        if (value instanceof CompoundNode compound) {
            return compound;
        }
        throw new AssertionError("expected compound field " + key + ", got " + value);
    }

    private static DecodedRegistry decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String registryId = readMinecraftString(input);
            int count = readVarInt(input);
            List<DecodedEntry> entries = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String entryId = readMinecraftString(input);
                if (!input.readBoolean()) {
                    throw new IOException("test vector unexpectedly omits entry data");
                }
                int rootType = input.readUnsignedByte();
                Node root = readPayload(input, rootType);
                if (!(root instanceof CompoundNode compound)) {
                    throw new IOException("registry entry root is not compound");
                }
                entries.add(new DecodedEntry(entryId, compound));
            }
            if (input.read() != -1) {
                throw new IOException("registry packet has trailing bytes");
            }
            return new DecodedRegistry(registryId, entries);
        }
    }

    private static Node readPayload(DataInputStream input, int type) throws IOException {
        return switch (type) {
            case 3 -> new IntNode(input.readInt());
            case 8 -> new StringNode(input.readUTF());
            case 9 -> {
                int elementType = input.readUnsignedByte();
                int size = input.readInt();
                if (size < 0 || size > 256) {
                    throw new IOException("invalid test-vector list size");
                }
                List<Node> values = new ArrayList<>();
                for (int index = 0; index < size; index++) {
                    values.add(readPayload(input, elementType));
                }
                yield new ListNode(elementType, values);
            }
            case 10 -> {
                LinkedHashMap<String, Node> fields = new LinkedHashMap<>();
                int fieldType;
                while ((fieldType = input.readUnsignedByte()) != 0) {
                    String name = input.readUTF();
                    if (fields.putIfAbsent(name, readPayload(input, fieldType)) != null) {
                        throw new IOException("duplicate NBT field in test vector");
                    }
                }
                yield new CompoundNode(fields);
            }
            default -> throw new IOException("unsupported NBT type in test vector: " + type);
        };
    }

    private static String readMinecraftString(DataInputStream input) throws IOException {
        int length = readVarInt(input);
        if (length < 0 || length > 32_767) {
            throw new IOException("invalid Minecraft string length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated Minecraft string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            int current = input.readUnsignedByte();
            value |= (current & 0x7F) << (index * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("overlong VarInt");
    }

    private sealed interface Node permits StringNode, IntNode, ListNode, CompoundNode {
    }

    private record StringNode(String value) implements Node {
    }

    private record IntNode(int value) implements Node {
    }

    private record ListNode(int elementType, List<Node> values) implements Node {
        private ListNode {
            values = List.copyOf(values);
        }
    }

    private record CompoundNode(Map<String, Node> values) implements Node {
        private CompoundNode {
            values = Map.copyOf(values);
        }
    }

    private record DecodedEntry(String id, CompoundNode data) {
    }

    private record DecodedRegistry(String registryId, List<DecodedEntry> entries) {
        private DecodedRegistry {
            entries = List.copyOf(entries);
        }
    }
}
