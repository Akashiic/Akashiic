package br.com.atmbrasil.lobby.velocity;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.Objects;

/**
 * Strict Minecraft 1.21.1 clientbound packet translator for reviewed BlockState ids.
 *
 * <p>The input includes the packet id. This class never changes or releases the input. A
 * non-null result is a newly allocated buffer owned by the caller. {@code null} means that a
 * reviewed packet was structurally valid but did not carry a BlockState needing translation.
 */
final class Minecraft1211BlockStatePacketTranslator {
    private static final int MAXIMUM_INPUT_PACKET_BYTES = 8 * 1024 * 1024;
    private static final int MAXIMUM_OUTPUT_PACKET_BYTES = 8 * 1024 * 1024;
    private static final int MAXIMUM_INPUT_CHUNK_DATA_BYTES = 2_097_152;
    private static final int MAXIMUM_OUTPUT_CHUNK_DATA_BYTES = 2_097_152;
    private static final int MAXIMUM_CHUNK_SECTIONS = 64;
    private static final int BLOCKS_PER_SECTION = 4_096;
    private static final int BIOMES_PER_SECTION = 64;
    private static final int MAXIMUM_NBT_DEPTH = 64;
    private static final int MAXIMUM_NBT_NODES = 262_144;
    private static final int BLOCK_DESTROY_LEVEL_EVENT = 2_001;
    private static final int BLOCK_BREAK_LEVEL_EVENT = 3_008;
    private static final int PARTICLE_FIXED_PREFIX_BYTES = 45;

    private final BlockStateTranslationProfile profile;

    Minecraft1211BlockStatePacketTranslator(BlockStateTranslationProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
        if (profile.minecraftProtocol() != 767) {
            throw new IllegalArgumentException(
                    "BlockState packet translator only supports Minecraft protocol 767");
        }
    }

    /**
     * Translates one reviewed clientbound packet without changing or retaining {@code input}.
     *
     * @return a newly allocated translated packet, or {@code null} for a reviewed no-op packet
     */
    ByteBuf translate(ByteBuf input, ByteBufAllocator allocator) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(allocator, "allocator");
        int inputBytes = input.readableBytes();
        if (inputBytes <= 0 || inputBytes > MAXIMUM_INPUT_PACKET_BYTES) {
            throw violation("packet length is outside the reviewed bound");
        }

        ByteBuf cursor = input.duplicate();
        int packetId = readCanonicalVarInt(cursor, "packet id");
        if (!profile.rewritesPacketId(packetId)) {
            throw violation("packet id is outside the reviewed translation set: " + packetId);
        }
        if (packetId == profile.levelChunkWithLightPacketId()) {
            return translateChunkWithLight(cursor, allocator, packetId, inputBytes);
        }
        if (packetId == profile.blockUpdatePacketId()) {
            return translateBlockUpdate(cursor, allocator, packetId, inputBytes);
        }
        if (packetId == profile.sectionBlocksUpdatePacketId()) {
            return translateSectionBlocksUpdate(cursor, allocator, packetId, inputBytes);
        }
        if (packetId == profile.levelEventPacketId()) {
            return translateLevelEvent(cursor, allocator, packetId, inputBytes);
        }
        if (packetId == profile.levelParticlesPacketId()) {
            return translateLevelParticles(cursor, allocator, packetId, inputBytes);
        }
        if (packetId == profile.addEntityPacketId()) {
            return translateAddEntity(cursor, allocator, packetId, inputBytes);
        }
        throw violation("reviewed packet id has no translator: " + packetId);
    }

    private ByteBuf translateBlockUpdate(
            ByteBuf input, ByteBufAllocator allocator, int packetId, int inputBytes) {
        long position = readLong(input, "block update position");
        int state = readCanonicalVarInt(input, "block update state");
        requireEnd(input, "block update");

        ByteBuf output = allocatePacket(allocator, inputBytes);
        try {
            writeVarInt(output, packetId);
            output.writeLong(position);
            writeVarInt(output, profile.translate(state));
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    private ByteBuf translateSectionBlocksUpdate(
            ByteBuf input, ByteBufAllocator allocator, int packetId, int inputBytes) {
        long sectionPosition = readLong(input, "section update position");
        int count = readCanonicalVarInt(input, "section update count");
        if (count > BLOCKS_PER_SECTION) {
            throw violation("section update count exceeds 4096");
        }

        long[] translated = new long[count];
        for (int index = 0; index < count; index++) {
            long packed = readCanonicalVarLong(input, "section update entry");
            int localPosition = (int) (packed & 0xFFFL);
            long rawState = packed >>> 12;
            if (rawState > Integer.MAX_VALUE) {
                throw violation("section update state exceeds integer range");
            }
            int state = profile.translate((int) rawState);
            translated[index] = ((long) state << 12) | localPosition;
        }
        requireEnd(input, "section update");

        ByteBuf output = allocatePacket(allocator, inputBytes + count);
        try {
            writeVarInt(output, packetId);
            output.writeLong(sectionPosition);
            writeVarInt(output, count);
            for (long packed : translated) {
                writeVarLong(output, packed);
            }
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    private ByteBuf translateLevelEvent(
            ByteBuf input, ByteBufAllocator allocator, int packetId, int inputBytes) {
        int eventId = readInt(input, "level event id");
        long position = readLong(input, "level event position");
        int data = readInt(input, "level event data");
        int global = readUnsignedByte(input, "level event global flag");
        if (global > 1) {
            throw violation("level event boolean is not canonical");
        }
        requireEnd(input, "level event");
        if (eventId != BLOCK_DESTROY_LEVEL_EVENT && eventId != BLOCK_BREAK_LEVEL_EVENT) {
            return null;
        }

        ByteBuf output = allocatePacket(allocator, inputBytes + 1);
        try {
            writeVarInt(output, packetId);
            output.writeInt(eventId);
            output.writeLong(position);
            output.writeInt(profile.translate(data));
            output.writeByte(global);
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    private ByteBuf translateLevelParticles(
            ByteBuf input, ByteBufAllocator allocator, int packetId, int inputBytes) {
        int fixedPrefixStart = input.readerIndex();
        requireReadable(input, PARTICLE_FIXED_PREFIX_BYTES, "particle fixed fields");
        int booleanValue = input.getUnsignedByte(fixedPrefixStart);
        if (booleanValue > 1) {
            throw violation("particle override-limiter boolean is not canonical");
        }
        input.skipBytes(PARTICLE_FIXED_PREFIX_BYTES);
        int particleType = readCanonicalVarInt(input, "particle type");
        if (!profile.isBlockParticleType(particleType)) {
            return null;
        }
        int state = readCanonicalVarInt(input, "block particle state");
        requireEnd(input, "block particle");

        ByteBuf output = allocatePacket(allocator, inputBytes + 1);
        try {
            writeVarInt(output, packetId);
            output.writeBytes(input, fixedPrefixStart, PARTICLE_FIXED_PREFIX_BYTES);
            writeVarInt(output, particleType);
            writeVarInt(output, profile.translate(state));
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    private ByteBuf translateAddEntity(
            ByteBuf input, ByteBufAllocator allocator, int packetId, int inputBytes) {
        int bodyStart = input.readerIndex();
        readCanonicalVarInt(input, "entity id");
        skip(input, 16, "entity UUID");
        int entityType = readCanonicalVarInt(input, "entity type");
        skip(input, 3 * Double.BYTES + 3, "entity position and rotations");
        int dataStart = input.readerIndex();
        int data = readCanonicalVarInt(input, "entity data");
        int dataEnd = input.readerIndex();
        skip(input, 3 * Short.BYTES, "entity velocity");
        requireEnd(input, "add entity");
        if (entityType != profile.fallingBlockEntityTypeId()) {
            return null;
        }

        ByteBuf output = allocatePacket(allocator, inputBytes + 1);
        try {
            writeVarInt(output, packetId);
            output.writeBytes(input, bodyStart, dataStart - bodyStart);
            writeVarInt(output, profile.translate(data));
            output.writeBytes(input, dataEnd, input.writerIndex() - dataEnd);
            return output;
        } catch (RuntimeException | Error failure) {
            output.release();
            throw failure;
        }
    }

    private ByteBuf translateChunkWithLight(
            ByteBuf input, ByteBufAllocator allocator, int packetId, int inputBytes) {
        int chunkX = readInt(input, "chunk x");
        int chunkZ = readInt(input, "chunk z");
        int heightmapsStart = input.readerIndex();
        skipHeightmapsNbt(input);
        int heightmapsEnd = input.readerIndex();
        int inputChunkBytes = readCanonicalVarInt(input, "chunk data length");
        if (inputChunkBytes <= 0 || inputChunkBytes > MAXIMUM_INPUT_CHUNK_DATA_BYTES) {
            throw violation("chunk data length is outside the reviewed bound");
        }
        requireReadable(input, inputChunkBytes, "chunk data");
        ByteBuf chunkInput = input.slice(input.readerIndex(), inputChunkBytes);
        input.skipBytes(inputChunkBytes);

        ByteBuf chunkOutput = allocator.buffer(
                Math.min(inputChunkBytes + 64 * 1024, MAXIMUM_OUTPUT_CHUNK_DATA_BYTES),
                MAXIMUM_OUTPUT_CHUNK_DATA_BYTES);
        try {
            translateChunkSections(chunkInput, chunkOutput);
            if (chunkInput.isReadable()) {
                throw violation("trailing bytes remain inside chunk section data");
            }

            int outputChunkBytes = chunkOutput.readableBytes();
            if (outputChunkBytes <= 0 || outputChunkBytes > MAXIMUM_OUTPUT_CHUNK_DATA_BYTES) {
                throw violation("translated chunk data length is outside the reviewed bound");
            }
            int estimatedPacketBytes = Math.addExact(
                    inputBytes, Math.max(0, outputChunkBytes - inputChunkBytes));
            ByteBuf output = allocatePacket(allocator, estimatedPacketBytes);
            try {
                writeVarInt(output, packetId);
                output.writeInt(chunkX);
                output.writeInt(chunkZ);
                output.writeBytes(input, heightmapsStart, heightmapsEnd - heightmapsStart);
                writeVarInt(output, outputChunkBytes);
                output.writeBytes(chunkOutput, chunkOutput.readerIndex(), outputChunkBytes);
                // Block entities and the complete light payload do not contain BlockState ids.
                output.writeBytes(input, input.readerIndex(), input.readableBytes());
                return output;
            } catch (RuntimeException | Error failure) {
                output.release();
                throw failure;
            }
        } finally {
            chunkOutput.release();
        }
    }

    private void translateChunkSections(ByteBuf input, ByteBuf output) {
        int sectionCount = 0;
        while (input.isReadable()) {
            sectionCount++;
            if (sectionCount > MAXIMUM_CHUNK_SECTIONS) {
                throw violation("chunk section count exceeds reviewed bound");
            }
            int nonEmptyBlocks = readUnsignedShort(input, "section non-empty block count");
            if (nonEmptyBlocks > BLOCKS_PER_SECTION) {
                throw violation("section non-empty block count exceeds 4096");
            }
            output.writeShort(nonEmptyBlocks);
            translateBlockPalette(input, output);

            int biomeStart = input.readerIndex();
            validateBiomePalette(input);
            output.writeBytes(input, biomeStart, input.readerIndex() - biomeStart);
        }
        if (sectionCount == 0) {
            throw violation("chunk has no sections");
        }
    }

    private void translateBlockPalette(ByteBuf input, ByteBuf output) {
        int bits = readUnsignedByte(input, "block palette bits");
        output.writeByte(bits == profile.sourceGlobalPaletteBits()
                ? profile.targetGlobalPaletteBits()
                : bits);
        if (bits == 0) {
            int state = readCanonicalVarInt(input, "singleton block state");
            writeVarInt(output, profile.translate(state));
            int dataLength = readCanonicalVarInt(input, "singleton block storage length");
            if (dataLength != 0) {
                throw violation("singleton block palette has packed data");
            }
            writeVarInt(output, 0);
            return;
        }
        if (bits >= 4 && bits <= 8) {
            translateLocalBlockPalette(input, output, bits);
            return;
        }
        if (bits == profile.sourceGlobalPaletteBits()) {
            translateGlobalBlockPalette(input, output, bits);
            return;
        }
        throw violation("unsupported block palette bits: " + bits);
    }

    private void translateLocalBlockPalette(ByteBuf input, ByteBuf output, int bits) {
        int paletteSize = readCanonicalVarInt(input, "local block palette size");
        if (paletteSize <= 0 || paletteSize > (1 << bits)) {
            throw violation("local block palette size exceeds bit capacity");
        }
        writeVarInt(output, paletteSize);
        for (int index = 0; index < paletteSize; index++) {
            int state = readCanonicalVarInt(input, "local block palette state");
            writeVarInt(output, profile.translate(state));
        }

        int expectedLongs = packedLongCount(BLOCKS_PER_SECTION, bits);
        int longCount = readCanonicalVarInt(input, "local block storage length");
        if (longCount != expectedLongs) {
            throw violation("local block storage length mismatch");
        }
        writeVarInt(output, longCount);
        long[] storage = readLongArray(input, longCount, "local block storage");
        validatePackedIndexes(storage, bits, BLOCKS_PER_SECTION, paletteSize);
        for (long value : storage) {
            output.writeLong(value);
        }
    }

    private void translateGlobalBlockPalette(ByteBuf input, ByteBuf output, int sourceBits) {
        int expectedLongs = packedLongCount(BLOCKS_PER_SECTION, sourceBits);
        int longCount = readCanonicalVarInt(input, "global block storage length");
        if (longCount != expectedLongs) {
            throw violation("global block storage length mismatch");
        }
        long[] source = readLongArray(input, longCount, "global block storage");
        int targetBits = profile.targetGlobalPaletteBits();
        long[] target = new long[packedLongCount(BLOCKS_PER_SECTION, targetBits)];
        for (int index = 0; index < BLOCKS_PER_SECTION; index++) {
            int state = (int) unpack(source, sourceBits, index);
            int translated = profile.translate(state);
            if ((translated & ~bitMask(targetBits)) != 0) {
                throw violation("translated global BlockState exceeds target bit width");
            }
            pack(target, targetBits, index, translated);
        }
        writeVarInt(output, target.length);
        for (long value : target) {
            output.writeLong(value);
        }
    }

    private static void validateBiomePalette(ByteBuf input) {
        int bits = readUnsignedByte(input, "biome palette bits");
        if (bits == 0) {
            readCanonicalVarInt(input, "singleton biome id");
            int dataLength = readCanonicalVarInt(input, "singleton biome storage length");
            if (dataLength != 0) {
                throw violation("singleton biome palette has packed data");
            }
            return;
        }

        int paletteSize = -1;
        if (bits >= 1 && bits <= 3) {
            paletteSize = readCanonicalVarInt(input, "local biome palette size");
            if (paletteSize <= 0 || paletteSize > (1 << bits)) {
                throw violation("local biome palette size exceeds bit capacity");
            }
            for (int index = 0; index < paletteSize; index++) {
                readCanonicalVarInt(input, "local biome palette id");
            }
        } else if (bits < 4 || bits > 31) {
            throw violation("unsupported global biome palette bits: " + bits);
        }

        int expectedLongs = packedLongCount(BIOMES_PER_SECTION, bits);
        int longCount = readCanonicalVarInt(input, "biome storage length");
        if (longCount != expectedLongs) {
            throw violation("biome storage length mismatch");
        }
        long[] storage = readLongArray(input, longCount, "biome storage");
        if (paletteSize > 0) {
            validatePackedIndexes(storage, bits, BIOMES_PER_SECTION, paletteSize);
        }
    }

    private static void skipHeightmapsNbt(ByteBuf input) {
        int rootType = readUnsignedByte(input, "heightmaps NBT root type");
        if (rootType != 10) {
            throw violation("heightmaps NBT root is not a compound");
        }
        int[] visitedNodes = {1};
        skipNbtPayload(input, rootType, 0, visitedNodes);
    }

    private static void skipNbtPayload(
            ByteBuf input, int type, int depth, int[] visitedNodes) {
        if (depth > MAXIMUM_NBT_DEPTH) {
            throw violation("heightmaps NBT exceeds maximum depth");
        }
        if (++visitedNodes[0] > MAXIMUM_NBT_NODES) {
            throw violation("heightmaps NBT exceeds node bound");
        }
        switch (type) {
            case 0 -> {
                // TAG_End has no payload.
            }
            case 1 -> skip(input, 1, "NBT byte");
            case 2 -> skip(input, 2, "NBT short");
            case 3, 5 -> skip(input, 4, "NBT four-byte scalar");
            case 4, 6 -> skip(input, 8, "NBT eight-byte scalar");
            case 7 -> skipNbtArray(input, 1, "NBT byte array");
            case 8 -> skipNbtString(input, "NBT string");
            case 9 -> {
                int elementType = readUnsignedByte(input, "NBT list element type");
                int length = readInt(input, "NBT list length");
                if (length < 0 || length > MAXIMUM_NBT_NODES - visitedNodes[0]) {
                    throw violation("NBT list length exceeds bound");
                }
                if (elementType == 0 && length != 0) {
                    throw violation("NBT list has TAG_End elements");
                }
                requireNbtType(elementType);
                for (int index = 0; index < length; index++) {
                    skipNbtPayload(input, elementType, depth + 1, visitedNodes);
                }
            }
            case 10 -> {
                while (true) {
                    int childType = readUnsignedByte(input, "NBT compound entry type");
                    if (childType == 0) {
                        break;
                    }
                    requireNbtType(childType);
                    skipNbtString(input, "NBT compound entry name");
                    skipNbtPayload(input, childType, depth + 1, visitedNodes);
                }
            }
            case 11 -> skipNbtArray(input, Integer.BYTES, "NBT int array");
            case 12 -> skipNbtArray(input, Long.BYTES, "NBT long array");
            default -> throw violation("unknown NBT type: " + type);
        }
    }

    private static void skipNbtArray(ByteBuf input, int elementBytes, String field) {
        int length = readInt(input, field + " length");
        if (length < 0) {
            throw violation(field + " has negative length");
        }
        long bytes = (long) length * elementBytes;
        if (bytes > Integer.MAX_VALUE) {
            throw violation(field + " byte length exceeds integer range");
        }
        skip(input, (int) bytes, field);
    }

    private static void skipNbtString(ByteBuf input, String field) {
        int length = readUnsignedShort(input, field + " length");
        skip(input, length, field);
    }

    private static void requireNbtType(int type) {
        if (type < 0 || type > 12) {
            throw violation("unknown NBT type: " + type);
        }
    }

    private static void validatePackedIndexes(
            long[] storage, int bits, int valueCount, int paletteSize) {
        for (int index = 0; index < valueCount; index++) {
            if (unpack(storage, bits, index) >= paletteSize) {
                throw violation("packed palette index is outside the local palette");
            }
        }
    }

    private static long unpack(long[] storage, int bits, int index) {
        int valuesPerLong = Long.SIZE / bits;
        int longIndex = index / valuesPerLong;
        int bitIndex = (index - longIndex * valuesPerLong) * bits;
        return storage[longIndex] >>> bitIndex & bitMask(bits);
    }

    private static void pack(long[] storage, int bits, int index, long value) {
        int valuesPerLong = Long.SIZE / bits;
        int longIndex = index / valuesPerLong;
        int bitIndex = (index - longIndex * valuesPerLong) * bits;
        storage[longIndex] |= value << bitIndex;
    }

    private static int packedLongCount(int valueCount, int bits) {
        int valuesPerLong = Long.SIZE / bits;
        if (valuesPerLong <= 0) {
            throw violation("packed storage bit width is invalid");
        }
        return (valueCount + valuesPerLong - 1) / valuesPerLong;
    }

    private static int bitMask(int bits) {
        if (bits <= 0 || bits >= Integer.SIZE) {
            throw violation("integer bit width is invalid");
        }
        return (1 << bits) - 1;
    }

    private static long[] readLongArray(ByteBuf input, int count, String field) {
        if (count < 0 || count > MAXIMUM_OUTPUT_CHUNK_DATA_BYTES / Long.BYTES) {
            throw violation(field + " count exceeds bound");
        }
        long byteCount = (long) count * Long.BYTES;
        if (byteCount > Integer.MAX_VALUE) {
            throw violation(field + " byte count exceeds integer range");
        }
        requireReadable(input, (int) byteCount, field);
        long[] values = new long[count];
        for (int index = 0; index < count; index++) {
            values[index] = input.readLong();
        }
        return values;
    }

    private static ByteBuf allocatePacket(ByteBufAllocator allocator, int estimatedBytes) {
        if (estimatedBytes <= 0 || estimatedBytes > MAXIMUM_OUTPUT_PACKET_BYTES) {
            throw violation("translated packet estimate exceeds reviewed bound");
        }
        return allocator.buffer(
                Math.max(64, estimatedBytes), MAXIMUM_OUTPUT_PACKET_BYTES);
    }

    private static int readCanonicalVarInt(ByteBuf input, String field) {
        int value = 0;
        for (int byteIndex = 0; byteIndex < 5; byteIndex++) {
            int current = readUnsignedByte(input, field);
            if (byteIndex == 4 && (current & 0xF8) != 0) {
                throw violation(field + " exceeds non-negative integer range");
            }
            value |= (current & 0x7F) << (byteIndex * 7);
            if ((current & 0x80) == 0) {
                if (varIntBytes(value) != byteIndex + 1) {
                    throw violation(field + " is not canonical");
                }
                return value;
            }
        }
        throw violation(field + " exceeds five bytes");
    }

    private static long readCanonicalVarLong(ByteBuf input, String field) {
        long value = 0;
        for (int byteIndex = 0; byteIndex < 9; byteIndex++) {
            int current = readUnsignedByte(input, field);
            if (byteIndex == 8 && (current & 0xFE) != 0) {
                throw violation(field + " exceeds non-negative long range");
            }
            value |= (long) (current & 0x7F) << (byteIndex * 7);
            if ((current & 0x80) == 0) {
                if (varLongBytes(value) != byteIndex + 1) {
                    throw violation(field + " is not canonical");
                }
                return value;
            }
        }
        throw violation(field + " exceeds nine bytes");
    }

    private static void writeVarInt(ByteBuf output, int value) {
        if (value < 0) {
            throw violation("cannot encode negative VarInt");
        }
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte(remaining & 0x7F | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    private static void writeVarLong(ByteBuf output, long value) {
        if (value < 0) {
            throw violation("cannot encode negative VarLong");
        }
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            output.writeByte((int) (remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte((int) remaining);
    }

    private static int varIntBytes(int value) {
        if ((value & ~0x7F) == 0) {
            return 1;
        }
        if ((value & ~0x3FFF) == 0) {
            return 2;
        }
        if ((value & ~0x1F_FFFF) == 0) {
            return 3;
        }
        if ((value & ~0x0FFF_FFFF) == 0) {
            return 4;
        }
        return 5;
    }

    private static int varLongBytes(long value) {
        int bytes = 1;
        long remaining = value >>> 7;
        while (remaining != 0) {
            bytes++;
            remaining >>>= 7;
        }
        return bytes;
    }

    private static int readUnsignedByte(ByteBuf input, String field) {
        requireReadable(input, 1, field);
        return input.readUnsignedByte();
    }

    private static int readUnsignedShort(ByteBuf input, String field) {
        requireReadable(input, Short.BYTES, field);
        return input.readUnsignedShort();
    }

    private static int readInt(ByteBuf input, String field) {
        requireReadable(input, Integer.BYTES, field);
        return input.readInt();
    }

    private static long readLong(ByteBuf input, String field) {
        requireReadable(input, Long.BYTES, field);
        return input.readLong();
    }

    private static void skip(ByteBuf input, int bytes, String field) {
        if (bytes < 0) {
            throw violation(field + " has negative byte length");
        }
        requireReadable(input, bytes, field);
        input.skipBytes(bytes);
    }

    private static void requireReadable(ByteBuf input, int bytes, String field) {
        if (bytes < 0 || input.readableBytes() < bytes) {
            throw violation("truncated " + field);
        }
    }

    private static void requireEnd(ByteBuf input, String packet) {
        if (input.isReadable()) {
            throw violation("trailing bytes in " + packet + " packet");
        }
    }

    private static IllegalArgumentException violation(String message) {
        return new IllegalArgumentException("invalid Minecraft 1.21.1 packet: " + message);
    }
}
