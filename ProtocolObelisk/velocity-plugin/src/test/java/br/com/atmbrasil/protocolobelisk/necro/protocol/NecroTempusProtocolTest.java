package br.com.atmbrasil.protocolobelisk.necro.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

final class NecroTempusProtocolTest {
    private static final int MAXIMUM_COMPRESSED = 30_000;
    private static final int MAXIMUM_DECOMPRESSED = 262_144;
    private static final UUID PLAYER_ID =
            UUID.fromString("e1f69e79-0432-33bb-ad07-2077994de007");
    private static final long EPOCH = 0x1020304050607080L;

    @Test
    void capabilityPlaneRoundTripsAndRejectsNonCanonicalFrames() throws Exception {
        BridgeProtocol.Capability confirmed = BridgeProtocol.Capability.confirmed(
                EPOCH, PLAYER_ID, "staging-4c6375d");
        assertEquals(confirmed, BridgeProtocol.decode(BridgeProtocol.encode(confirmed)));

        BridgeProtocol.Capability request = BridgeProtocol.Capability.requested(PLAYER_ID);
        byte[] requestFrame = BridgeProtocol.encode(request);
        assertEquals(32, requestFrame.length);
        assertEquals(request, BridgeProtocol.decode(requestFrame));
        assertEquals(0L, request.connectionEpoch());
        assertTrue(BridgeProtocol.CAPABILITY_CHANNEL.length() <= 20);

        byte[] wrongMagic = requestFrame.clone();
        wrongMagic[0] = 0;
        assertThrows(
                BridgeProtocol.ProtocolException.class,
                () -> BridgeProtocol.decode(wrongMagic));
        byte[] trailing = Arrays.copyOf(requestFrame, requestFrame.length + 1);
        assertThrows(
                BridgeProtocol.ProtocolException.class,
                () -> BridgeProtocol.decode(trailing));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BridgeProtocol.Capability(
                        BridgeProtocol.Operation.REQUEST, EPOCH, PLAYER_ID, ""));
    }

    @Test
    void helloUsesExactSignedShortFramingAndStrictIdentity() throws Exception {
        byte[] hello = NecroTempusPacketCodec.encodeHelloFixture("1.3.3-test");
        NecroTempusPacketCodec.Hello decoded = NecroTempusPacketCodec.decodeClientHello(
                hello, 4_096, 65_536);
        assertEquals("1.3.3-test", decoded.version());
        assertEquals(hello.length - 3, decoded.compressedBytes());
        assertEquals(NecroTempusPacketCodec.CLIENT_HELLO_DISCRIMINATOR,
                Byte.toUnsignedInt(hello[0]));

        byte[] trailing = Arrays.copyOf(hello, hello.length + 1);
        assertThrows(
                NecroTempusPacketCodec.CodecException.class,
                () -> NecroTempusPacketCodec.decodeClientHello(trailing, 4_096, 65_536));
        byte[] negativeSignedLength = hello.clone();
        negativeSignedLength[1] = (byte) 0x80;
        negativeSignedLength[2] = 0;
        assertThrows(
                NecroTempusPacketCodec.CodecException.class,
                () -> NecroTempusPacketCodec.decodeClientHello(
                        negativeSignedLength, 4_096, 65_536));
        assertThrows(
                IllegalArgumentException.class,
                () -> NecroTempusPacketCodec.encodeHelloFixture("bad\u0000version"));
    }

    @Test
    void everyClientboundDiscriminatorEnforcesItsOperationSet() throws Exception {
        assertAccepted(NecroTempusPacketCodec.BOSS_BAR_DISCRIMINATOR, "add");
        assertAccepted(NecroTempusPacketCodec.BOSS_BAR_DISCRIMINATOR, "remove");
        assertAccepted(NecroTempusPacketCodec.BOSS_BAR_DISCRIMINATOR, "update");
        for (int discriminator = NecroTempusPacketCodec.PLAYER_TAB_DISCRIMINATOR;
                discriminator <= NecroTempusPacketCodec.ACTION_BAR_DISCRIMINATOR;
                discriminator++) {
            assertAccepted(discriminator, "set");
            assertAccepted(discriminator, "remove");
            byte[] invalid = frameStringCompound(
                    discriminator, Map.of("packetType", "update"));
            assertThrows(
                    NecroTempusPacketCodec.CodecException.class,
                    () -> NecroTempusPacketCodec.validateClientbound(
                            invalid, MAXIMUM_COMPRESSED, MAXIMUM_DECOMPRESSED));
        }
    }

    @Test
    void playerTabEncoderProducesWireCompatibleNbt() throws Exception {
        byte[] set = NecroTempusPacketCodec.encodePlayerTabSet(
                "§6Rede Akashic\n§fLobby",
                "§7discord.gg/exemplo",
                MAXIMUM_COMPRESSED);
        NecroTempusPacketCodec.ClientboundPacket decoded =
                NecroTempusPacketCodec.validateClientbound(
                        set, MAXIMUM_COMPRESSED, MAXIMUM_DECOMPRESSED);
        assertEquals(NecroTempusPacketCodec.PLAYER_TAB_DISCRIMINATOR,
                decoded.discriminator());
        assertEquals("set", decoded.stringFields().get("packetType"));
        assertTrue(decoded.stringFields().get("header").contains("Rede Akashic"));
        assertTrue(decoded.stringFields().get("footer").contains("discord.gg"));

        byte[] remove = NecroTempusPacketCodec.encodePlayerTabRemove(MAXIMUM_COMPRESSED);
        NecroTempusPacketCodec.ClientboundPacket removed =
                NecroTempusPacketCodec.validateClientbound(
                        remove, MAXIMUM_COMPRESSED, MAXIMUM_DECOMPRESSED);
        assertEquals("remove", removed.stringFields().get("packetType"));
    }

    @Test
    void tabBridgeIsFixedBoundExactAndStrictUtf8() throws Exception {
        String header = "§4FORBIDDEN";
        String footer = "§7Footer";
        byte[] set = TabBridgeProtocol.encodeSet(
                EPOCH, PLAYER_ID, 1L, header, footer, 16_384, MAXIMUM_COMPRESSED);
        assertEquals(
                42 + header.getBytes(StandardCharsets.UTF_8).length
                        + footer.getBytes(StandardCharsets.UTF_8).length,
                set.length);
        TabBridgeProtocol.Snapshot decoded =
                TabBridgeProtocol.decode(set, 16_384, MAXIMUM_COMPRESSED);
        assertEquals(TabBridgeProtocol.Operation.SET, decoded.operation());
        assertEquals(EPOCH, decoded.connectionEpoch());
        assertEquals(PLAYER_ID, decoded.playerId());
        assertEquals(1L, decoded.sequence());
        assertEquals(header, decoded.header());
        assertEquals(footer, decoded.footer());
        assertTrue(TabBridgeProtocol.CHANNEL.length() <= 20);

        byte[] remove = TabBridgeProtocol.encodeRemove(
                EPOCH, PLAYER_ID, 2L, MAXIMUM_COMPRESSED);
        TabBridgeProtocol.Snapshot removed =
                TabBridgeProtocol.decode(remove, 16_384, MAXIMUM_COMPRESSED);
        assertEquals(TabBridgeProtocol.Operation.REMOVE, removed.operation());
        assertTrue(removed.header().isEmpty());
        assertTrue(removed.footer().isEmpty());

        byte[] trailing = Arrays.copyOf(set, set.length + 1);
        assertThrows(
                TabBridgeProtocol.ProtocolException.class,
                () -> TabBridgeProtocol.decode(trailing, 16_384, MAXIMUM_COMPRESSED));
        byte[] wrongMagic = set.clone();
        wrongMagic[0] = 0;
        assertThrows(
                TabBridgeProtocol.ProtocolException.class,
                () -> TabBridgeProtocol.decode(wrongMagic, 16_384, MAXIMUM_COMPRESSED));

        byte[] invalidUtf8 = TabBridgeProtocol.encodeSet(
                EPOCH, PLAYER_ID, 3L, "ab", "", 16_384, MAXIMUM_COMPRESSED);
        invalidUtf8[42] = (byte) 0xC3;
        invalidUtf8[43] = 0x28;
        assertThrows(
                TabBridgeProtocol.ProtocolException.class,
                () -> TabBridgeProtocol.decode(invalidUtf8, 16_384, MAXIMUM_COMPRESSED));
    }

    @Test
    void legacyTextCanonicalizationPreservesVisibleSemanticsWithinBounds() {
        String converted = LegacyTextSanitizer.normalize(
                "§x§f§f§0§0§0§0Teste", 1_024, 10);
        assertTrue(converted.startsWith("§4") || converted.startsWith("§c"));
        assertFalse(converted.contains("§x"));
        assertEquals("a\nb", LegacyTextSanitizer.normalize("a\nb\nc", 1_024, 2));
        assertEquals(
                "§e§m━━━━",
                LegacyTextSanitizer.normalize("§e§m━━§r§e§m━━", 1_024, 2));
        assertEquals(
                "§e§mA§rB",
                LegacyTextSanitizer.normalize("§e§mA§rB", 1_024, 2));
        assertEquals("ab\tc\nd", LegacyTextSanitizer.normalize(
                "a\u0000b\tc\r\nd", 1_024, 10));
        assertEquals("12345", LegacyTextSanitizer.normalize("123456789", 5, 10));
        assertEquals("😀", LegacyTextSanitizer.normalize("😀x", 4, 10));
    }

    @Test
    void nbtReaderRejectsBombsInvalidListsAndExcessiveDepth() throws Exception {
        byte[] zeroArray = framedNbtWithExtra((byte) 7, 0);
        assertEquals(
                "set",
                NecroTempusPacketCodec.validateClientbound(
                                zeroArray, 4_096, 65_536)
                        .stringFields()
                        .get("packetType"));

        byte[] invalidEndList = framedNbtWithExtra((byte) 9, 1);
        assertThrows(
                NecroTempusPacketCodec.CodecException.class,
                () -> NecroTempusPacketCodec.validateClientbound(
                        invalidEndList, 4_096, 65_536));
        byte[] oversizedArray = framedNbtWithExtra((byte) 7, 16_385);
        assertThrows(
                NecroTempusPacketCodec.CodecException.class,
                () -> NecroTempusPacketCodec.validateClientbound(
                        oversizedArray, 4_096, 65_536));
        byte[] nested = framedNestedCompounds(34);
        assertThrows(
                NecroTempusPacketCodec.CodecException.class,
                () -> NecroTempusPacketCodec.validateClientbound(nested, 4_096, 65_536));

        byte[] compressible = NecroTempusPacketCodec.encodePlayerTabSet(
                "x".repeat(8_192), "", MAXIMUM_COMPRESSED);
        assertThrows(
                NecroTempusPacketCodec.CodecException.class,
                () -> NecroTempusPacketCodec.validateClientbound(
                        compressible, MAXIMUM_COMPRESSED, 128));
    }

    @Test
    void fixedWindowLimiterEnforcesBothPacketAndByteBudgets() {
        WindowRateLimiter limiter = new WindowRateLimiter(2, 10);
        assertTrue(limiter.tryAcquire(4));
        assertTrue(limiter.tryAcquire(6));
        assertFalse(limiter.tryAcquire(0));
        assertFalse(new WindowRateLimiter(10, 5).tryAcquire(6));
        assertFalse(new WindowRateLimiter(10, 5).tryAcquire(-1));
    }

    private static void assertAccepted(int discriminator, String operation) throws Exception {
        byte[] payload = frameStringCompound(
                discriminator, Map.of("packetType", operation));
        NecroTempusPacketCodec.ClientboundPacket decoded =
                NecroTempusPacketCodec.validateClientbound(
                        payload, MAXIMUM_COMPRESSED, MAXIMUM_DECOMPRESSED);
        assertEquals(discriminator, decoded.discriminator());
        assertEquals(operation, decoded.stringFields().get("packetType"));
    }

    private static byte[] frameStringCompound(int discriminator, Map<String, String> fields)
            throws IOException {
        return frame(discriminator, LegacyNbt.writeStringCompound(fields));
    }

    private static byte[] framedNbtWithExtra(byte extraTagType, int length)
            throws IOException {
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressedBytes);
                DataOutputStream data = new DataOutputStream(gzip)) {
            data.writeByte(10);
            data.writeUTF("");
            data.writeByte(8);
            data.writeUTF("packetType");
            data.writeUTF("set");
            if (extraTagType == 7) {
                data.writeByte(7);
                data.writeUTF("bytes");
                data.writeInt(length);
            } else {
                data.writeByte(9);
                data.writeUTF("list");
                data.writeByte(0);
                data.writeInt(length);
            }
            data.writeByte(0);
        }
        return frame(
                NecroTempusPacketCodec.PLAYER_TAB_DISCRIMINATOR,
                compressedBytes.toByteArray());
    }

    private static byte[] framedNestedCompounds(int depth) throws IOException {
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressedBytes);
                DataOutputStream data = new DataOutputStream(gzip)) {
            data.writeByte(10);
            data.writeUTF("");
            for (int index = 0; index < depth; index++) {
                data.writeByte(10);
                data.writeUTF("nested");
            }
            for (int index = 0; index <= depth; index++) {
                data.writeByte(0);
            }
        }
        return frame(
                NecroTempusPacketCodec.PLAYER_TAB_DISCRIMINATOR,
                compressedBytes.toByteArray());
    }

    private static byte[] frame(int discriminator, byte[] compressed) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream(compressed.length + 3);
        try (DataOutputStream data = new DataOutputStream(framed)) {
            data.writeByte(discriminator);
            data.writeShort(compressed.length);
            data.write(compressed);
        }
        byte[] result = framed.toByteArray();
        assertArrayEquals(compressed, Arrays.copyOfRange(result, 3, result.length));
        return result;
    }
}
