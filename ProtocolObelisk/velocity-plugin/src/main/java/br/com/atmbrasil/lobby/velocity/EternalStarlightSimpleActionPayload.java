package br.com.atmbrasil.lobby.velocity;

import java.util.Arrays;

/** Exact C2S bodies accepted by Eternal Starlight's shared simple-action codec. */
final class EternalStarlightSimpleActionPayload {
    static final int PAYLOAD_BYTES = 13;

    // ByteBufCodecs.STRING_UTF8: one canonical VarInt byte (12), then twelve ASCII bytes.
    private static final byte[] SWING_ATTACK = {
        12, 's', 'w', 'i', 'n', 'g', '_', 'a', 't', 't', 'a', 'c', 'k'
    };
    private static final byte[] SWITCH_CREST = {
        12, 's', 'w', 'i', 't', 'c', 'h', '_', 'c', 'r', 'e', 's', 't'
    };

    private EternalStarlightSimpleActionPayload() {}

    static boolean isValid(byte[] payload) {
        return payload != null
                && payload.length == PAYLOAD_BYTES
                && (Arrays.equals(payload, SWING_ATTACK)
                        || Arrays.equals(payload, SWITCH_CREST));
    }
}
