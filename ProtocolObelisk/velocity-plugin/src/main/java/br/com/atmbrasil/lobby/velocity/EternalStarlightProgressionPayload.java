package br.com.atmbrasil.lobby.velocity;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict validator for Eternal Starlight's periodic client progression observation. */
final class EternalStarlightProgressionPayload {
    static final int MAXIMUM_ENTRIES = 64;
    static final int MAXIMUM_RESOURCE_LOCATION_UTF8_BYTES = 256;
    private static final Pattern RESOURCE_LOCATION =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private EternalStarlightProgressionPayload() {}

    static boolean isValid(byte[] payload) {
        if (payload == null || payload.length < Integer.BYTES) {
            return false;
        }
        ByteBuffer input = ByteBuffer.wrap(payload);
        int count = input.getInt();
        if (count < 0 || count > MAXIMUM_ENTRIES) {
            return false;
        }

        Set<String> entries = new HashSet<>();
        for (int index = 0; index < count; index++) {
            int byteLength = readCanonicalVarInt(input);
            if (byteLength < 1
                    || byteLength > MAXIMUM_RESOURCE_LOCATION_UTF8_BYTES
                    || byteLength > input.remaining()) {
                return false;
            }
            byte[] encoded = new byte[byteLength];
            input.get(encoded);
            String resourceLocation;
            try {
                resourceLocation = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(encoded))
                        .toString();
            } catch (CharacterCodingException exception) {
                return false;
            }
            if (!RESOURCE_LOCATION.matcher(resourceLocation).matches()
                    || !entries.add(resourceLocation)) {
                return false;
            }
        }
        return !input.hasRemaining();
    }

    private static int readCanonicalVarInt(ByteBuffer input) {
        int value = 0;
        int bytes = 0;
        while (bytes < 5 && input.hasRemaining()) {
            int current = Byte.toUnsignedInt(input.get());
            value |= (current & 0x7f) << (bytes * 7);
            bytes++;
            if ((current & 0x80) == 0) {
                return encodedVarIntBytes(value) == bytes ? value : -1;
            }
        }
        return -1;
    }

    private static int encodedVarIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7f) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }
}
