package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ArsNouveauEnchantmentRegistryTest {
    private static final List<String> EXPECTED_IDS = List.of(
            "ars_nouveau:mana_boost",
            "ars_nouveau:mana_regen",
            "ars_nouveau:reactive");

    @Test
    void exact5113RegistryContainsAllThreeReviewedEnchantments() throws Exception {
        DecodedRegistry registry = decode(ArsNouveauEnchantmentRegistry.packetBodyForTest());

        assertEquals("minecraft:enchantment", registry.registryId());
        assertEquals(EXPECTED_IDS, registry.entries().stream()
                .map(DecodedEntry::id)
                .toList());

        CompoundNode manaBoost = registry.entries().getFirst().data();
        CompoundNode boostEffects = compound(manaBoost, "effects");
        ListNode boostAttributes = list(boostEffects, "minecraft:attributes");
        CompoundNode boostEffect = compound(boostAttributes.values().getFirst());
        assertEquals(
                new StringNode("ars_nouveau:ars_nouveau.perk.max_mana"),
                boostEffect.values().get("attribute"));
        CompoundNode boostAmount = compound(boostEffect, "amount");
        assertEquals(new FloatNode(25.0F), boostAmount.values().get("base"));
        assertEquals(
                new FloatNode(25.0F),
                boostAmount.values().get("per_level_above_first"));

        CompoundNode manaRegen = registry.entries().get(1).data();
        CompoundNode regenEffect = compound(
                list(compound(manaRegen, "effects"), "minecraft:attributes")
                        .values().getFirst());
        assertEquals(
                new StringNode("ars_nouveau:ars_nouveau.perk.mana_regen"),
                regenEffect.values().get("attribute"));
        assertEquals(
                new FloatNode(2.0F),
                compound(regenEffect, "amount").values().get("base"));

        CompoundNode reactive = registry.entries().get(2).data();
        assertFalse(reactive.values().containsKey("effects"));
        assertEquals(new IntNode(4), reactive.values().get("max_level"));
        assertEquals(
                new StringNode("neoforge:any"),
                compound(reactive, "supported_items").values().get("type"));
        assertEquals(
                new ListNode(8, List.of(new StringNode("any"))),
                reactive.values().get("slots"));
    }

    @Test
    void packetIsDefensiveBoundedAndHasAStableSelfVerifiedDigest() throws Exception {
        RegistryShimPacket packet = ArsNouveauEnchantmentRegistry.packet(65_536);
        byte[] first = packet.packetBody();
        byte[] second = packet.packetBody();
        assertEquals("ars_nouveau", packet.requiredNamespace());
        assertEquals(3, packet.entryCount());
        assertNotSame(first, second);
        assertArrayEquals(first, second);
        first[0] ^= 0x7F;
        assertArrayEquals(second, packet.packetBody());
        assertEquals(1_382, second.length);
        assertEquals(
                "36c513b5dbd903607d0fa897b3337a079914e36b4668a776daa621572c602d13",
                ArsNouveauEnchantmentRegistry.packetSha256ForTest());
        assertEquals(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(second)),
                ArsNouveauEnchantmentRegistry.packetSha256ForTest());
        assertThrows(IllegalArgumentException.class, () ->
                ArsNouveauEnchantmentRegistry.packet(second.length - 1));
    }

    @Test
    void immutablePacketCreatesIndependentZeroCopyReadOnlyViews() {
        RegistryShimPacket packet = ArsNouveauEnchantmentRegistry.packet(65_536);
        ByteBuf first = packet.newReadOnlyPacketBody();
        ByteBuf second = packet.newReadOnlyPacketBody();
        try {
            assertTrue(first.isReadOnly());
            assertTrue(second.isReadOnly());
            assertNotSame(first, second);
            int secondReaderIndex = second.readerIndex();
            first.readByte();
            assertTrue(first.readerIndex() > secondReaderIndex);
            assertEquals(secondReaderIndex, second.readerIndex());
            assertThrows(ReadOnlyBufferException.class, () -> first.setByte(0, 0));
            assertArrayEquals(packet.packetBody(), ByteBufUtil.getBytes(second));

            ByteBuf released = packet.newReadOnlyPacketBody();
            released.release();
            assertThrows(
                    io.netty.util.IllegalReferenceCountException.class,
                    released::readByte);
        } finally {
            first.release();
            second.release();
        }
    }

    @Test
    void catalogNeverUsesPartialEnchantmentShimsForExactAtm10Normal81Contract() {
        List<RegistryShimPacket> resolved = RegistryShimCatalog.resolve(
                RegistryShimCatalog.builtInAtmShimIds().stream()
                        .filter(id -> !id.equals(
                                RegistryShimCatalog.FULL_ENCHANTMENT_ATM10_8_1))
                        .toList(),
                65_536);
        String exactContract =
                Atm10Normal81IronsSpellbooksRegistry.FULL_CLIENT_CONTRACT_SHA256;

        assertEquals(5, resolved.size());
        assertEquals(
                List.of(RegistryShimCatalog.NEOVITAE_SENTIENT_ATM10_8_1),
                RegistryShimCatalog.selectForProfile(
                                null, resolved, List.of("ars_nouveau"), exactContract)
                        .stream().map(RegistryShimPacket::shimId).toList());
        assertEquals(
                List.of(
                        "forbidden-arcanus-2.6.1",
                        RegistryShimCatalog.NEOVITAE_SENTIENT_ATM10_8_1),
                RegistryShimCatalog.selectForProfile(
                                null, resolved, List.of("forbidden_arcanus"), exactContract)
                        .stream().map(RegistryShimPacket::shimId).toList());
        assertEquals(2, RegistryShimCatalog.selectForProfile(
                null,
                resolved,
                List.of("ars_nouveau", "forbidden_arcanus"),
                exactContract).size());
        assertEquals(
                List.of(RegistryShimCatalog.NEOVITAE_SENTIENT_ATM10_8_1),
                RegistryShimCatalog.selectForProfile(
                        null, resolved, List.of("minecraft", "neoforge"), exactContract)
                        .stream().map(RegistryShimPacket::shimId).toList());
        assertEquals(List.of(), RegistryShimCatalog.selectForProfile(
                null, resolved, List.of("ars_nouveau"), "0".repeat(64)));
        assertEquals(List.of(), RegistryShimCatalog.selectForProfile(
                null, resolved, List.of("ars_nouveau"), null));
        assertThrows(IllegalArgumentException.class, () ->
                RegistryShimCatalog.resolve(List.of("arbitrary-payload"), 65_536));
    }

    @Test
    void shimDescriptorRejectsUnboundedOrNonCanonicalMetadata() {
        byte[] body = {1};
        String digest = "0".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> new RegistryShimPacket(
                "Invalid Shim", "ars_nouveau", "minecraft:enchantment", 1, body, digest));
        assertThrows(IllegalArgumentException.class, () -> new RegistryShimPacket(
                "valid-shim", "Ars Nouveau", "minecraft:enchantment", 1, body, digest));
        assertThrows(IllegalArgumentException.class, () -> new RegistryShimPacket(
                "valid-shim", "ars_nouveau", "not-a-registry", 1, body, digest));
        assertThrows(IllegalArgumentException.class, () -> new RegistryShimPacket(
                "valid-shim", "ars_nouveau", "minecraft:enchantment", 1, body, "bad"));
    }

    private static CompoundNode compound(CompoundNode parent, String key) {
        return compound(parent.values().get(key));
    }

    private static CompoundNode compound(Node value) {
        if (value instanceof CompoundNode compound) {
            return compound;
        }
        throw new AssertionError("expected compound, got " + value);
    }

    private static ListNode list(CompoundNode parent, String key) {
        Node value = parent.values().get(key);
        if (value instanceof ListNode list) {
            return list;
        }
        throw new AssertionError("expected list field " + key + ", got " + value);
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
            case 5 -> new FloatNode(input.readFloat());
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

    private sealed interface Node
            permits StringNode, IntNode, FloatNode, ListNode, CompoundNode {
    }

    private record StringNode(String value) implements Node {
    }

    private record IntNode(int value) implements Node {
    }

    private record FloatNode(float value) implements Node {
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
