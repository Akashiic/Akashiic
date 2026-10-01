package br.com.atmbrasil.lobby.velocity;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict wire validator shared by EnderDrives' item and fluid type-count requests. */
final class EnderDrivesTypeCountRequestPayload {
    /** Default {@code FriendlyByteBuf.readUtf()} character bound used by STRING_UTF8. */
    static final int MAXIMUM_SCOPE_PREFIX_CHARACTERS = 32_767;
    static final int MAXIMUM_SCOPE_PREFIX_UTF8_BYTES =
            MAXIMUM_SCOPE_PREFIX_CHARACTERS * 3;
    static final int MINIMUM_PAYLOAD_BYTES = 3;
    static final int MAXIMUM_PAYLOAD_BYTES =
            3 + MAXIMUM_SCOPE_PREFIX_UTF8_BYTES + 5 + 5;

    private EnderDrivesTypeCountRequestPayload() {}

    static boolean hasValidSize(int payloadBytes) {
        return payloadBytes >= MINIMUM_PAYLOAD_BYTES
                && payloadBytes <= MAXIMUM_PAYLOAD_BYTES;
    }

    static boolean isValid(byte[] payload) {
        if (payload == null || !hasValidSize(payload.length)) {
            return false;
        }

        ByteBuffer input = ByteBuffer.wrap(payload);
        DecodedVarInt encodedLength = readCanonicalVarInt(input);
        if (encodedLength == null
                || encodedLength.value() < 0
                || encodedLength.value() > MAXIMUM_SCOPE_PREFIX_UTF8_BYTES
                || encodedLength.value() > input.remaining()) {
            return false;
        }

        byte[] encodedScopePrefix = new byte[encodedLength.value()];
        input.get(encodedScopePrefix);
        try {
            String scopePrefix = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encodedScopePrefix))
                    .toString();
            if (scopePrefix.length() > MAXIMUM_SCOPE_PREFIX_CHARACTERS) {
                return false;
            }
        } catch (CharacterCodingException exception) {
            return false;
        }

        DecodedVarInt frequency = readCanonicalVarInt(input);
        DecodedVarInt typeLimit = readCanonicalVarInt(input);
        return frequency != null && typeLimit != null && !input.hasRemaining();
    }

    private static DecodedVarInt readCanonicalVarInt(ByteBuffer input) {
        int start = input.position();
        int value = 0;
        for (int index = 0; index < 5 && input.hasRemaining(); index++) {
            int current = Byte.toUnsignedInt(input.get());
            value |= (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) {
                int encodedBytes = index + 1;
                if (encodedVarIntBytes(value) != encodedBytes
                        || !matchesCanonicalEncoding(input, start, value, encodedBytes)) {
                    return null;
                }
                return new DecodedVarInt(value);
            }
        }
        return null;
    }

    private static boolean matchesCanonicalEncoding(
            ByteBuffer input, int start, int value, int encodedBytes) {
        int remaining = value;
        for (int index = 0; index < encodedBytes; index++) {
            int expected = remaining & 0x7f;
            remaining >>>= 7;
            if (index + 1 < encodedBytes) {
                expected |= 0x80;
            }
            if (Byte.toUnsignedInt(input.get(start + index)) != expected) {
                return false;
            }
        }
        return true;
    }

    private static int encodedVarIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7f) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    private record DecodedVarInt(int value) {}
}
