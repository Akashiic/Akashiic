package br.com.atmbrasil.protocolobelisk.necro.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Strict UTF-8 helper for small protocol identity tokens such as mod versions. */
final class Utf8Token {
    private Utf8Token() {
    }

    static byte[] encode(String value, int maximumBytes, String label) {
        Objects.requireNonNull(value, "value");
        validateMaximum(maximumBytes);
        String normalized = value.trim();
        validateCharacters(normalized, label);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(normalized));
            if (encoded.remaining() > maximumBytes) {
                throw new IllegalArgumentException(label + " is too long");
            }
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(label + " contains invalid Unicode", exception);
        }
    }

    static String decode(byte[] bytes, int maximumBytes, String label) {
        Objects.requireNonNull(bytes, "bytes");
        validateMaximum(maximumBytes);
        if (bytes.length > maximumBytes) {
            throw new IllegalArgumentException(label + " is too long");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .trim();
            validateCharacters(decoded, label);
            return decoded;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(label + " is not valid UTF-8", exception);
        }
    }

    private static void validateMaximum(int maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
    }

    private static void validateCharacters(String value, String label) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is empty");
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException(label + " contains control characters");
            }
            offset += Character.charCount(codePoint);
        }
    }
}
