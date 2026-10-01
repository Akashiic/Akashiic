package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import org.junit.jupiter.api.Test;

final class ApothicEnchantingBootstrapPayloadTest {
    @Test
    void normal80OfficialMapIsSeparateEvidenceForTheSharedSentinelCodec() {
        assertEquals(
                "5f8aa1e956bb05b4ea3eb1ca34753c68b5718aac77f9cf3bd8d9a3cc4916234a",
                ApothicEnchantingBootstrapPayload.ATM10_NORMAL_8_0_OFFICIAL_PAYLOAD_SHA256);
        assertNotEquals(
                ApothicEnchantingBootstrapPayload.ATM10_NORMAL_OFFICIAL_PAYLOAD_SHA256,
                ApothicEnchantingBootstrapPayload.ATM10_NORMAL_8_0_OFFICIAL_PAYLOAD_SHA256);
    }

    @Test
    void exact152PayloadSeedsOneRegistryBackedFallbackEntry() throws Exception {
        byte[] payload = ApothicEnchantingBootstrapPayload.payload();

        assertEquals(13, payload.length);
        assertEquals(
                "9aa6d67a2b268693a5a1674d18f72efebf846ded271647e596c312e4a31fb468",
                ApothicEnchantingBootstrapPayload.payloadSha256());
        assertEquals("apothic_enchanting:enchantment_info",
                ApothicEnchantingBootstrapPayload.CHANNEL_ID);
        assertEquals("1", ApothicEnchantingBootstrapPayload.CHANNEL_VERSION);
        assertEquals("1.21.1-1.5.2",
                ApothicEnchantingBootstrapPayload.REVIEWED_MOD_VERSION);
        assertEquals("1.21.1-1.6.0",
                ApothicEnchantingBootstrapPayload.ATM10_NORMAL_REVIEWED_MOD_VERSION);
        assertEquals("1.21.1-1.6.1",
                ApothicEnchantingBootstrapPayload.ATM10_8_1_REVIEWED_MOD_VERSION);
        assertEquals(135,
                ApothicEnchantingBootstrapPayload.ATM10_NORMAL_OFFICIAL_ENTRY_COUNT);
        assertEquals(1_643,
                ApothicEnchantingBootstrapPayload.ATM10_NORMAL_OFFICIAL_PAYLOAD_BYTES);
        assertEquals(
                "5b3d6b28ada63c5791add176954a13752d26b8a1dbd058ce2c51427738011224",
                ApothicEnchantingBootstrapPayload.ATM10_NORMAL_OFFICIAL_PAYLOAD_SHA256);

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals(1, readVarInt(input));
            assertEquals(0, readVarInt(input));
            assertEquals(0, readVarInt(input));
            assertEquals(1, readVarInt(input));
            assertEquals(1, readVarInt(input));
            assertEquals(-1, readVarInt(input));
            assertEquals(1, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
            assertEquals(0, readVarInt(input));
            assertEquals(-1, input.read());
        }
    }

    @Test
    void payloadOwnershipIsDefensive() {
        byte[] first = ApothicEnchantingBootstrapPayload.payload();
        byte[] second = ApothicEnchantingBootstrapPayload.payload();

        assertNotSame(first, second);
        assertArrayEquals(first, second);
        first[0] ^= 0x7F;
        assertArrayEquals(second, ApothicEnchantingBootstrapPayload.payload());
    }

    private static int readVarInt(DataInputStream input) throws Exception {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            int current = input.readUnsignedByte();
            value |= (current & 0x7F) << (index * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("overlong VarInt in reviewed payload");
    }
}
