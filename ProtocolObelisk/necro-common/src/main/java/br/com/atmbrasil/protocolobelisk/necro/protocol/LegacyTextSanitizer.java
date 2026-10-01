package br.com.atmbrasil.protocolobelisk.necro.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Bounded legacy-text canonicalizer for Minecraft 1.7.10.
 *
 * <p>Besides lowering modern RGB section sequences to the nearest legacy colour, this class
 * canonicalizes formatting runs. TAB components often serialize a decorative line as repeated
 * {@code §r§e§m} fragments. Those fragments rebuild exactly the style that was already active,
 * but create separate rendering runs in the 1.7.10 font renderer and can leave visible seams in
 * strikethrough/underline decorations. Canonicalization removes only semantically redundant
 * transitions while preserving every visible character and effective style.</p>
 */
public final class LegacyTextSanitizer {
    private static final char SECTION = '\u00A7';
    private static final String COLOURS = "0123456789abcdef";
    private static final int[][] PALETTE = {
        {0x00, 0x00, 0x00}, {0x00, 0x00, 0xAA}, {0x00, 0xAA, 0x00}, {0x00, 0xAA, 0xAA},
        {0xAA, 0x00, 0x00}, {0xAA, 0x00, 0xAA}, {0xFF, 0xAA, 0x00}, {0xAA, 0xAA, 0xAA},
        {0x55, 0x55, 0x55}, {0x55, 0x55, 0xFF}, {0x55, 0xFF, 0x55}, {0x55, 0xFF, 0xFF},
        {0xFF, 0x55, 0x55}, {0xFF, 0x55, 0xFF}, {0xFF, 0xFF, 0x55}, {0xFF, 0xFF, 0xFF}
    };

    private LegacyTextSanitizer() {
    }

    public static String normalize(String input, int maximumUtf8Bytes, int maximumLines) {
        if (maximumUtf8Bytes <= 0 || maximumLines <= 0) {
            throw new IllegalArgumentException("text limits must be positive");
        }
        if (input == null || input.isEmpty()) {
            return "";
        }

        String normalized = input.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder output = new StringBuilder(normalized.length());
        Style desired = new Style();
        Style emitted = new Style();
        int lines = 1;

        for (int index = 0; index < normalized.length();) {
            char character = normalized.charAt(index);
            if (character == '\n') {
                if (lines >= maximumLines) {
                    break;
                }
                emitTransition(output, emitted, desired);
                output.append(character);
                emitted.copyFrom(desired);
                lines++;
                index++;
                continue;
            }
            if (isRgbSequence(normalized, index)) {
                int red = hexPair(normalized.charAt(index + 3), normalized.charAt(index + 5));
                int green = hexPair(normalized.charAt(index + 7), normalized.charAt(index + 9));
                int blue = hexPair(normalized.charAt(index + 11), normalized.charAt(index + 13));
                desired.setColour(closestLegacyCode(red, green, blue));
                index += 14;
                continue;
            }
            if (character == SECTION && index + 1 < normalized.length()) {
                char code = Character.toLowerCase(normalized.charAt(index + 1));
                if (desired.apply(code)) {
                    index += 2;
                    continue;
                }
            }
            if (Character.isISOControl(character) && character != '\t') {
                index++;
                continue;
            }

            emitTransition(output, emitted, desired);
            output.append(character);
            emitted.copyFrom(desired);
            index++;
        }
        return truncateUtf8(output.toString(), maximumUtf8Bytes);
    }

    private static void emitTransition(StringBuilder output, Style current, Style target) {
        if (current.sameAs(target)) {
            return;
        }
        if (current.colour != target.colour) {
            if (target.colour == 0) {
                output.append(SECTION).append('r');
            } else {
                output.append(SECTION).append(target.colour);
            }
            appendFormats(output, target);
            return;
        }

        if (current.hasOnlySubsetOf(target)) {
            appendAddedFormats(output, current, target);
            return;
        }

        output.append(SECTION).append('r');
        if (target.colour != 0) {
            output.append(SECTION).append(target.colour);
        }
        appendFormats(output, target);
    }

    private static void appendFormats(StringBuilder output, Style style) {
        if (style.obfuscated) output.append(SECTION).append('k');
        if (style.bold) output.append(SECTION).append('l');
        if (style.strikethrough) output.append(SECTION).append('m');
        if (style.underline) output.append(SECTION).append('n');
        if (style.italic) output.append(SECTION).append('o');
    }

    private static void appendAddedFormats(StringBuilder output, Style current, Style target) {
        if (!current.obfuscated && target.obfuscated) output.append(SECTION).append('k');
        if (!current.bold && target.bold) output.append(SECTION).append('l');
        if (!current.strikethrough && target.strikethrough) output.append(SECTION).append('m');
        if (!current.underline && target.underline) output.append(SECTION).append('n');
        if (!current.italic && target.italic) output.append(SECTION).append('o');
    }

    private static char closestLegacyCode(int red, int green, int blue) {
        long bestDistance = Long.MAX_VALUE;
        int best = 15;
        for (int index = 0; index < PALETTE.length; index++) {
            long redDelta = red - PALETTE[index][0];
            long greenDelta = green - PALETTE[index][1];
            long blueDelta = blue - PALETTE[index][2];
            long distance = 30L * redDelta * redDelta
                    + 59L * greenDelta * greenDelta
                    + 11L * blueDelta * blueDelta;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return COLOURS.charAt(best);
    }

    private static boolean isRgbSequence(String text, int offset) {
        if (offset + 14 > text.length()
                || text.charAt(offset) != SECTION
                || Character.toLowerCase(text.charAt(offset + 1)) != 'x') {
            return false;
        }
        for (int pair = 0; pair < 6; pair++) {
            int sectionIndex = offset + 2 + pair * 2;
            if (text.charAt(sectionIndex) != SECTION
                    || Character.digit(text.charAt(sectionIndex + 1), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int hexPair(char high, char low) {
        return Character.digit(high, 16) * 16 + Character.digit(low, 16);
    }

    private static String truncateUtf8(String value, int maximumBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes) {
            return value;
        }
        StringBuilder output = new StringBuilder(value.length());
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int encodedBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + encodedBytes > maximumBytes) {
                break;
            }
            output.append(character);
            bytes += encodedBytes;
            offset += Character.charCount(codePoint);
        }
        if (output.length() > 0 && output.charAt(output.length() - 1) == SECTION) {
            output.setLength(output.length() - 1);
        }
        return output.toString();
    }

    private static final class Style {
        private char colour;
        private boolean obfuscated;
        private boolean bold;
        private boolean strikethrough;
        private boolean underline;
        private boolean italic;

        boolean apply(char code) {
            if (COLOURS.indexOf(code) >= 0) {
                setColour(code);
                return true;
            }
            switch (code) {
                case 'k': obfuscated = true; return true;
                case 'l': bold = true; return true;
                case 'm': strikethrough = true; return true;
                case 'n': underline = true; return true;
                case 'o': italic = true; return true;
                case 'r': reset(); return true;
                default: return false;
            }
        }

        void setColour(char code) {
            colour = code;
            obfuscated = false;
            bold = false;
            strikethrough = false;
            underline = false;
            italic = false;
        }

        void reset() {
            colour = 0;
            obfuscated = false;
            bold = false;
            strikethrough = false;
            underline = false;
            italic = false;
        }

        boolean sameAs(Style other) {
            return colour == other.colour
                    && obfuscated == other.obfuscated
                    && bold == other.bold
                    && strikethrough == other.strikethrough
                    && underline == other.underline
                    && italic == other.italic;
        }

        boolean hasOnlySubsetOf(Style other) {
            return colour == other.colour
                    && (!obfuscated || other.obfuscated)
                    && (!bold || other.bold)
                    && (!strikethrough || other.strikethrough)
                    && (!underline || other.underline)
                    && (!italic || other.italic);
        }

        void copyFrom(Style other) {
            colour = other.colour;
            obfuscated = other.obfuscated;
            bold = other.bold;
            strikethrough = other.strikethrough;
            underline = other.underline;
            italic = other.italic;
        }
    }
}
