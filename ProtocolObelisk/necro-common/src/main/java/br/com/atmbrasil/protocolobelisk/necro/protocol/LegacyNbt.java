package br.com.atmbrasil.protocolobelisk.necro.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Minimal, bounded Minecraft 1.7 NBT codec for the NecroTempus control packets. */
public final class LegacyNbt {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;
    private static final int MAXIMUM_DEPTH = 32;
    private static final int MAXIMUM_COLLECTION_ELEMENTS = 16_384;

    private LegacyNbt() {
    }

    public static byte[] writePlayerTab(
            String packetType,
            boolean drawPlayerHeads,
            String header,
            String footer) throws IOException {
        Objects.requireNonNull(packetType, "packetType");
        ByteArrayOutputStream output = new ByteArrayOutputStream(512);
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(output))) {
            data.writeByte(TAG_COMPOUND);
            data.writeUTF("");

            writeEmptyCompoundList(data, "cellList");
            writeByte(data, "drawPlayerHeads", drawPlayerHeads ? 1 : 0);
            if (header != null && !header.isEmpty()) {
                writeString(data, "header", header);
            }
            if (footer != null && !footer.isEmpty()) {
                writeString(data, "footer", footer);
            }
            writeString(data, "packetType", packetType);
            data.writeByte(TAG_END);
        }
        return output.toByteArray();
    }

    static byte[] writeStringCompound(Map<String, String> values) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(128);
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(output))) {
            data.writeByte(TAG_COMPOUND);
            data.writeUTF("");
            for (Map.Entry<String, String> entry : values.entrySet()) {
                writeString(data, entry.getKey(), entry.getValue());
            }
            data.writeByte(TAG_END);
        }
        return output.toByteArray();
    }

    public static Map<String, String> readRootStringFields(
            byte[] compressed,
            int maximumDecompressedBytes) throws IOException {
        Objects.requireNonNull(compressed, "compressed");
        if (maximumDecompressedBytes <= 0) {
            throw new IllegalArgumentException("maximumDecompressedBytes must be positive");
        }
        byte[] decompressed;
        try (InputStream raw = new ByteArrayInputStream(compressed);
                GZIPInputStream gzip = new GZIPInputStream(raw);
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        Math.min(maximumDecompressedBytes, 8192))) {
            byte[] buffer = new byte[4096];
            int total = 0;
            while (true) {
                int read = gzip.read(buffer);
                if (read < 0) {
                    break;
                }
                total = Math.addExact(total, read);
                if (total > maximumDecompressedBytes) {
                    throw new IOException("decompressed NBT exceeds its bound");
                }
                output.write(buffer, 0, read);
            }
            decompressed = output.toByteArray();
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(decompressed))) {
            int rootType = data.readUnsignedByte();
            if (rootType != TAG_COMPOUND) {
                throw new IOException("root tag is not a compound");
            }
            data.readUTF();
            Map<String, String> result = new LinkedHashMap<>();
            readCompound(data, result, 0);
            if (data.available() != 0) {
                throw new IOException("trailing bytes after root NBT compound");
            }
            return Map.copyOf(result);
        } catch (EOFException exception) {
            throw new IOException("truncated NBT", exception);
        }
    }

    private static void readCompound(DataInput data, Map<String, String> result, int depth)
            throws IOException {
        requireDepth(depth);
        while (true) {
            int type = data.readUnsignedByte();
            if (type == TAG_END) {
                return;
            }
            String name = data.readUTF();
            if (type == TAG_STRING) {
                result.putIfAbsent(name, data.readUTF());
            } else {
                skipPayload(data, type, depth + 1);
            }
        }
    }

    private static void skipPayload(DataInput data, int type, int depth) throws IOException {
        requireDepth(depth);
        switch (type) {
            case TAG_BYTE -> data.readByte();
            case TAG_SHORT -> data.readShort();
            case TAG_INT, TAG_FLOAT -> data.readInt();
            case TAG_LONG, TAG_DOUBLE -> data.readLong();
            case TAG_BYTE_ARRAY -> skipFully(data, checkedLength(data.readInt(), 0));
            case TAG_STRING -> data.readUTF();
            case TAG_LIST -> {
                int elementType = data.readUnsignedByte();
                int length = checkedLength(data.readInt(), 0);
                if (elementType == TAG_END && length != 0) {
                    throw new IOException("TAG_End list type is valid only for an empty list");
                }
                for (int index = 0; index < length; index++) {
                    skipPayload(data, elementType, depth + 1);
                }
            }
            case TAG_COMPOUND -> {
                while (true) {
                    int nestedType = data.readUnsignedByte();
                    if (nestedType == TAG_END) {
                        break;
                    }
                    data.readUTF();
                    skipPayload(data, nestedType, depth + 1);
                }
            }
            case TAG_INT_ARRAY -> skipFully(data, Math.multiplyExact(checkedLength(data.readInt(), 0), 4));
            case TAG_LONG_ARRAY -> skipFully(data, Math.multiplyExact(checkedLength(data.readInt(), 0), 8));
            case TAG_END -> {
                // End has no payload. It is valid as a list element type only for an empty list.
            }
            default -> throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static int checkedLength(int length, int minimum) throws IOException {
        if (length < minimum || length > MAXIMUM_COLLECTION_ELEMENTS) {
            throw new IOException("invalid NBT collection length " + length);
        }
        return length;
    }

    private static void skipFully(DataInput input, int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            int skipped = input.skipBytes(remaining);
            if (skipped <= 0) {
                input.readByte();
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void requireDepth(int depth) throws IOException {
        if (depth > MAXIMUM_DEPTH) {
            throw new IOException("NBT nesting is too deep");
        }
    }

    private static void writeEmptyCompoundList(DataOutputStream data, String name)
            throws IOException {
        data.writeByte(TAG_LIST);
        data.writeUTF(name);
        data.writeByte(TAG_COMPOUND);
        data.writeInt(0);
    }

    private static void writeByte(DataOutputStream data, String name, int value)
            throws IOException {
        data.writeByte(TAG_BYTE);
        data.writeUTF(name);
        data.writeByte(value);
    }

    private static void writeString(DataOutputStream data, String name, String value)
            throws IOException {
        data.writeByte(TAG_STRING);
        data.writeUTF(name);
        data.writeUTF(value);
    }


}
