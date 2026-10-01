package br.com.atmbrasil.lobby.velocity.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.CompoundTag;
import br.com.atmbrasil.lobby.velocity.protocol.MinecraftRegistryPacketCodec.Entry;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MinecraftRegistryPacketCodecTest {
    @Test
    void encodesMinecraftRegistryEnvelopeAndNetworkNbtGoldenVector() throws Exception {
        byte[] actual = MinecraftRegistryPacketCodec.encode(
                "example:test",
                List.of(new Entry(
                        "example:value",
                        new CompoundTag(Map.of(
                                "name", MinecraftRegistryPacketCodec.stringTag("value"))))),
                1_024);

        byte[] expected = HexFormat.of().parseHex(
                "0c6578616d706c653a74657374"
                        + "01"
                        + "0d6578616d706c653a76616c7565"
                        + "01"
                        + "0a"
                        + "0800046e616d65000576616c7565"
                        + "00");
        assertArrayEquals(expected, actual);
    }

    @Test
    void encodesFiniteFloatTagWithMinecraftNetworkNbtTypeFive() throws Exception {
        LinkedHashMap<String, MinecraftRegistryPacketCodec.NbtValue> fields =
                new LinkedHashMap<>();
        fields.put("amount", MinecraftRegistryPacketCodec.floatTag(1.5F));

        byte[] actual = MinecraftRegistryPacketCodec.encode(
                "example:test",
                List.of(new Entry("example:value", new CompoundTag(fields))),
                1_024);

        byte[] expected = HexFormat.of().parseHex(
                "0c6578616d706c653a74657374"
                        + "01"
                        + "0d6578616d706c653a76616c7565"
                        + "01"
                        + "0a"
                        + "050006616d6f756e743fc00000"
                        + "00");
        assertArrayEquals(expected, actual);
        assertThrows(IllegalArgumentException.class, () ->
                MinecraftRegistryPacketCodec.floatTag(Float.NaN));
        assertThrows(IllegalArgumentException.class, () ->
                MinecraftRegistryPacketCodec.floatTag(Float.POSITIVE_INFINITY));
    }

    @Test
    void rejectsDuplicateInvalidAndOverBudgetPackets() {
        Entry entry = new Entry(
                "example:value",
                new CompoundTag(Map.of(
                        "name", MinecraftRegistryPacketCodec.stringTag("value"))));

        assertThrows(ProtocolViolationException.class, () ->
                MinecraftRegistryPacketCodec.encode("INVALID:test", List.of(entry), 1_024));
        assertThrows(ProtocolViolationException.class, () ->
                MinecraftRegistryPacketCodec.encode(
                        "example:test", List.of(entry, entry), 1_024));
        assertThrows(ProtocolViolationException.class, () ->
                MinecraftRegistryPacketCodec.encode("example:test", List.of(entry), 8));
    }
}
