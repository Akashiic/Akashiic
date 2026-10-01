package br.com.atmbrasil.lobby.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.atmbrasil.lobby.common.LobbyReadyCodec.LobbyReady;
import br.com.atmbrasil.lobby.common.LobbyReadyCodec.ProtocolException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class LobbyReadyCodecTest {
    @Test
    void channelIdentityRemainsStableAcrossTheRoutingRemoval() {
        assertEquals("atm10bridge:control", LobbyReadyCodec.CHANNEL_ID);
    }

    @Test
    void readinessSignalHasOneCanonicalFixedLengthEncoding() throws Exception {
        LobbyReady ready = new LobbyReady(0x1020304050607080L);
        byte[] encoded = LobbyReadyCodec.encode(ready);

        assertEquals(LobbyReadyCodec.PAYLOAD_BYTES, encoded.length);
        assertEquals(ready, LobbyReadyCodec.decode(encoded));
        assertArrayEquals(encoded, LobbyReadyCodec.encode(LobbyReadyCodec.decode(encoded)));
    }

    @Test
    void malformedOrAmbiguousSignalsFailClosed() {
        byte[] valid = LobbyReadyCodec.encode(new LobbyReady(42L));
        byte[] wrongMagic = valid.clone();
        wrongMagic[0] ^= 1;
        byte[] wrongVersion = valid.clone();
        wrongVersion[4] = 2;
        byte[] wrongOpcode = valid.clone();
        wrongOpcode[5] = 2;
        byte[] zeroSession = valid.clone();
        Arrays.fill(zeroSession, 6, zeroSession.length, (byte) 0);

        assertThrows(ProtocolException.class, () -> LobbyReadyCodec.decode(wrongMagic));
        assertThrows(ProtocolException.class, () -> LobbyReadyCodec.decode(wrongVersion));
        assertThrows(ProtocolException.class, () -> LobbyReadyCodec.decode(wrongOpcode));
        assertThrows(ProtocolException.class, () -> LobbyReadyCodec.decode(zeroSession));
        assertThrows(ProtocolException.class,
                () -> LobbyReadyCodec.decode(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> new LobbyReady(0L));
    }
}
