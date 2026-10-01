package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class Minecraft1211BlockStatePacketTranslatorTest {
    private static final String PROFILE_ID =
            "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247";
    private static final String FULL_CONTRACT =
            "a61293a54b0f80c83b73b5e2968e8de08b9595771110fa7c7cbea9e55ad2892f";
    private static final String FROZEN_SEQUENCE =
            "8fda0099a55ad36898adbf43dc77d5b702c6d4c5c26c1bcddb3039669dd00269";
    private static final ByteBufAllocator ALLOCATOR = UnpooledByteBufAllocator.DEFAULT;
    private static final int STONE_BRICKS_SOURCE = 6_537;
    private static final int STONE_BRICKS_TARGET = 9_029;

    private static BlockStateTranslationProfile profile;
    private static Minecraft1211BlockStatePacketTranslator translator;

    @BeforeAll
    static void loadProfile() throws IOException {
        profile = BlockStateTranslationProfile.loadAtm10Normal80(
                Minecraft1211BlockStatePacketTranslatorTest.class.getClassLoader(),
                SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT,
                PROFILE_ID,
                767,
                FULL_CONTRACT,
                FROZEN_SEQUENCE);
        translator = new Minecraft1211BlockStatePacketTranslator(profile);
        assertEquals(STONE_BRICKS_TARGET, profile.translate(STONE_BRICKS_SOURCE));
    }

    @Test
    void blockUpdateTranslatesWithoutChangingOrRetainingInput() {
        ByteBuf input = Unpooled.buffer();
        input.writeByte(0x7F);
        writeVarInt(input, profile.blockUpdatePacketId());
        input.writeLong(0x1234_5678_9ABC_DEF0L);
        writeVarInt(input, STONE_BRICKS_SOURCE);
        input.readerIndex(1);
        int initialReaderIndex = input.readerIndex();
        int initialReferenceCount = input.refCnt();

        ByteBuf output = translator.translate(input, ALLOCATOR);
        try {
            assertEquals(initialReaderIndex, input.readerIndex());
            assertEquals(initialReferenceCount, input.refCnt());
            assertEquals(profile.blockUpdatePacketId(), readVarInt(output));
            assertEquals(0x1234_5678_9ABC_DEF0L, output.readLong());
            assertEquals(STONE_BRICKS_TARGET, readVarInt(output));
            assertFalse(output.isReadable());
        } finally {
            output.release();
            input.release();
        }
    }

    @Test
    void blockUpdateRejectsNonCanonicalTruncatedOutOfRangeAndTrailingData() {
        assertPacketRejected(bytes(
                profile.blockUpdatePacketId(),
                0, 0, 0, 0, 0, 0, 0, 0,
                0x80, 0x00));
        assertPacketRejected(bytes(profile.blockUpdatePacketId(), 0, 0, 0));

        ByteBuf outOfRange = Unpooled.buffer();
        writeVarInt(outOfRange, profile.blockUpdatePacketId());
        outOfRange.writeLong(0L);
        writeVarInt(outOfRange, profile.sourceStateCount());
        assertPacketRejected(outOfRange);

        ByteBuf trailing = Unpooled.buffer();
        writeVarInt(trailing, profile.blockUpdatePacketId());
        trailing.writeLong(0L);
        writeVarInt(trailing, 0);
        trailing.writeByte(0);
        assertPacketRejected(trailing);
    }

    @Test
    void sectionUpdateTranslatesEveryEntryAndPreservesLocalPositions() {
        ByteBuf input = Unpooled.buffer();
        writeVarInt(input, profile.sectionBlocksUpdatePacketId());
        input.writeLong(0x0123_4567_89AB_CDEFL);
        writeVarInt(input, 3);
        writeVarLong(input, 5);
        writeVarLong(input, ((long) 47 << 12) | 0xABCL);
        writeVarLong(input, ((long) STONE_BRICKS_SOURCE << 12) | 0xFFFL);

        ByteBuf output = translator.translate(input, ALLOCATOR);
        try {
            assertEquals(profile.sectionBlocksUpdatePacketId(), readVarInt(output));
            assertEquals(0x0123_4567_89AB_CDEFL, output.readLong());
            assertEquals(3, readVarInt(output));
            assertEquals(5L, readVarLong(output));
            assertEquals(((long) profile.translate(47) << 12) | 0xABCL,
                    readVarLong(output));
            assertEquals(((long) STONE_BRICKS_TARGET << 12) | 0xFFFL,
                    readVarLong(output));
            assertFalse(output.isReadable());
        } finally {
            output.release();
            input.release();
        }
    }

    @Test
    void sectionUpdateRejectsExcessCountNonCanonicalVarLongAndTrailingData() {
        ByteBuf excessive = Unpooled.buffer();
        writeVarInt(excessive, profile.sectionBlocksUpdatePacketId());
        excessive.writeLong(0L);
        writeVarInt(excessive, 4_097);
        assertPacketRejected(excessive);

        ByteBuf nonCanonical = Unpooled.buffer();
        writeVarInt(nonCanonical, profile.sectionBlocksUpdatePacketId());
        nonCanonical.writeLong(0L);
        writeVarInt(nonCanonical, 1);
        nonCanonical.writeByte(0x80).writeByte(0);
        assertPacketRejected(nonCanonical);

        ByteBuf trailing = Unpooled.buffer();
        writeVarInt(trailing, profile.sectionBlocksUpdatePacketId());
        trailing.writeLong(0L);
        writeVarInt(trailing, 0);
        trailing.writeByte(0);
        assertPacketRejected(trailing);
    }

    @ParameterizedTest
    @ValueSource(ints = {2_001, 3_008})
    void reviewedLevelEventsTranslateBlockState(int eventId) {
        ByteBuf input = levelEvent(eventId, STONE_BRICKS_SOURCE, 1);
        ByteBuf output = translator.translate(input, ALLOCATOR);
        try {
            assertEquals(profile.levelEventPacketId(), readVarInt(output));
            assertEquals(eventId, output.readInt());
            assertEquals(123L, output.readLong());
            assertEquals(STONE_BRICKS_TARGET, output.readInt());
            assertEquals(1, output.readUnsignedByte());
            assertFalse(output.isReadable());
        } finally {
            output.release();
            input.release();
        }
    }

    @Test
    void unrelatedLevelEventIsValidatedAndPassedThroughAsNull() {
        ByteBuf input = levelEvent(1_001, Integer.MAX_VALUE, 0);
        int readerIndex = input.readerIndex();
        int referenceCount = input.refCnt();
        assertNull(translator.translate(input, ALLOCATOR));
        assertEquals(readerIndex, input.readerIndex());
        assertEquals(referenceCount, input.refCnt());
        input.release();
    }

    @Test
    void levelEventRejectsInvalidBooleanOutOfRangeStateAndTrailingData() {
        assertPacketRejected(levelEvent(2_001, STONE_BRICKS_SOURCE, 2));
        assertPacketRejected(levelEvent(2_001, profile.sourceStateCount(), 0));
        ByteBuf trailing = levelEvent(1_001, 0, 0);
        trailing.writeByte(0);
        assertPacketRejected(trailing);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 28, 105})
    void reviewedBlockParticlesTranslateTheirBlockState(int particleType) {
        ByteBuf input = blockParticle(particleType, STONE_BRICKS_SOURCE, 0);
        ByteBuf output = translator.translate(input, ALLOCATOR);
        try {
            assertEquals(profile.levelParticlesPacketId(), readVarInt(output));
            assertEquals(0, output.readUnsignedByte());
            output.skipBytes(44);
            assertEquals(particleType, readVarInt(output));
            assertEquals(STONE_BRICKS_TARGET, readVarInt(output));
            assertFalse(output.isReadable());
        } finally {
            output.release();
            input.release();
        }
    }

    @Test
    void unrelatedParticleIsPassedThroughWithoutParsingItsTypeSpecificPayload() {
        ByteBuf input = blockParticle(0, STONE_BRICKS_SOURCE, 0);
        int readerIndex = input.readerIndex();
        int referenceCount = input.refCnt();
        assertNull(translator.translate(input, ALLOCATOR));
        assertEquals(readerIndex, input.readerIndex());
        assertEquals(referenceCount, input.refCnt());
        input.release();
    }

    @Test
    void blockParticleRejectsInvalidBooleanNonCanonicalStateAndTrailingData() {
        assertPacketRejected(blockParticle(1, 0, 2));

        ByteBuf nonCanonical = Unpooled.buffer();
        writeVarInt(nonCanonical, profile.levelParticlesPacketId());
        nonCanonical.writeZero(45);
        writeVarInt(nonCanonical, 1);
        nonCanonical.writeByte(0x80).writeByte(0);
        assertPacketRejected(nonCanonical);

        ByteBuf trailing = blockParticle(1, 0, 0);
        trailing.writeByte(0);
        assertPacketRejected(trailing);
    }

    @Test
    void fallingBlockSpawnTranslatesDataAndOtherEntityPassesThrough() {
        ByteBuf falling = addEntity(profile.fallingBlockEntityTypeId(), STONE_BRICKS_SOURCE);
        ByteBuf output = translator.translate(falling, ALLOCATOR);
        try {
            assertEquals(profile.addEntityPacketId(), readVarInt(output));
            assertEquals(77, readVarInt(output));
            output.skipBytes(16);
            assertEquals(profile.fallingBlockEntityTypeId(), readVarInt(output));
            output.skipBytes(27);
            assertEquals(STONE_BRICKS_TARGET, readVarInt(output));
            assertEquals(11, output.readShort());
            assertEquals(12, output.readShort());
            assertEquals(13, output.readShort());
            assertFalse(output.isReadable());
        } finally {
            output.release();
            falling.release();
        }

        ByteBuf other = addEntity(1, Integer.MAX_VALUE);
        assertNull(translator.translate(other, ALLOCATOR));
        other.release();
    }

    @Test
    void addEntityRejectsTruncationTrailingDataAndNonCanonicalFields() {
        ByteBuf truncated = addEntity(profile.fallingBlockEntityTypeId(), 0);
        truncated.writerIndex(truncated.writerIndex() - 1);
        assertPacketRejected(truncated);

        ByteBuf trailing = addEntity(1, 0);
        trailing.writeByte(0);
        assertPacketRejected(trailing);

        ByteBuf nonCanonicalId = Unpooled.buffer();
        writeVarInt(nonCanonicalId, profile.addEntityPacketId());
        nonCanonicalId.writeByte(0x80).writeByte(0);
        nonCanonicalId.writeZero(16);
        writeVarInt(nonCanonicalId, 1);
        nonCanonicalId.writeZero(27);
        writeVarInt(nonCanonicalId, 0);
        nonCanonicalId.writeZero(6);
        assertPacketRejected(nonCanonicalId);
    }

    @Test
    void chunkTranslatesSingletonLocalAndGlobalPalettesAndCopiesOpaqueTail() {
        int[] localPalette = {0, 47, STONE_BRICKS_SOURCE};
        long[] localStorage = new long[packedLongCount(4_096, 4)];
        for (int index = 0; index < 4_096; index++) {
            pack(localStorage, 4, index, index % localPalette.length);
        }
        int[] globalStates = IntStream.range(0, 4_096)
                .map(index -> switch (index & 3) {
                    case 0 -> 0;
                    case 1 -> 47;
                    case 2 -> STONE_BRICKS_SOURCE;
                    default -> profile.sourceStateCount() - 1;
                })
                .toArray();
        long[] globalStorage = packValues(globalStates, profile.sourceGlobalPaletteBits());

        ByteBuf chunkData = Unpooled.buffer();
        writeSingletonSection(chunkData, STONE_BRICKS_SOURCE, 1);
        writeLocalSection(chunkData, localPalette, localStorage, 2);
        writeGlobalSection(chunkData, globalStorage, 3);
        byte[] heightmaps = reviewedHeightmapsNbt();
        byte[] tail = {2, 0x12, 0x34, 0x56, 0x78, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        ByteBuf input = chunkPacket(heightmaps, chunkData, tail);
        int inputReaderIndex = input.readerIndex();

        ByteBuf output = translator.translate(input, ALLOCATOR);
        try {
            assertEquals(inputReaderIndex, input.readerIndex());
            assertEquals(profile.levelChunkWithLightPacketId(), readVarInt(output));
            assertEquals(17, output.readInt());
            assertEquals(-23, output.readInt());
            byte[] actualHeightmaps = new byte[heightmaps.length];
            output.readBytes(actualHeightmaps);
            assertArrayEquals(heightmaps, actualHeightmaps);
            int outputChunkLength = readVarInt(output);
            ByteBuf translatedChunk = output.readSlice(outputChunkLength);

            assertEquals(1, translatedChunk.readUnsignedShort());
            assertEquals(0, translatedChunk.readUnsignedByte());
            assertEquals(STONE_BRICKS_TARGET, readVarInt(translatedChunk));
            assertEquals(0, readVarInt(translatedChunk));
            assertSingletonBiome(translatedChunk, 1);

            assertEquals(2, translatedChunk.readUnsignedShort());
            assertEquals(4, translatedChunk.readUnsignedByte());
            assertEquals(localPalette.length, readVarInt(translatedChunk));
            for (int state : localPalette) {
                assertEquals(profile.translate(state), readVarInt(translatedChunk));
            }
            assertEquals(localStorage.length, readVarInt(translatedChunk));
            for (long expected : localStorage) {
                assertEquals(expected, translatedChunk.readLong());
            }
            assertSingletonBiome(translatedChunk, 2);

            assertEquals(3, translatedChunk.readUnsignedShort());
            assertEquals(profile.targetGlobalPaletteBits(), translatedChunk.readUnsignedByte());
            int targetLongCount = readVarInt(translatedChunk);
            assertEquals(packedLongCount(4_096, profile.targetGlobalPaletteBits()),
                    targetLongCount);
            long[] targetStorage = new long[targetLongCount];
            for (int index = 0; index < targetStorage.length; index++) {
                targetStorage[index] = translatedChunk.readLong();
            }
            for (int index = 0; index < globalStates.length; index++) {
                assertEquals(profile.translate(globalStates[index]),
                        unpack(targetStorage, profile.targetGlobalPaletteBits(), index));
            }
            assertSingletonBiome(translatedChunk, 3);
            assertFalse(translatedChunk.isReadable());

            byte[] actualTail = new byte[output.readableBytes()];
            output.readBytes(actualTail);
            assertArrayEquals(tail, actualTail);
        } finally {
            output.release();
            input.release();
            chunkData.release();
        }
    }

    @Test
    void chunkCopiesValidLocalBiomePaletteUnchanged() {
        ByteBuf chunkData = Unpooled.buffer();
        chunkData.writeShort(0);
        writeSingletonBlockPalette(chunkData, 0);
        writeLocalBiomePalette(chunkData);
        byte[] sourceChunk = bytesOf(chunkData);
        ByteBuf input = chunkPacket(new byte[] {10, 0}, chunkData, new byte[] {0});
        ByteBuf output = translator.translate(input, ALLOCATOR);
        try {
            readVarInt(output);
            output.skipBytes(8 + 2);
            int length = readVarInt(output);
            byte[] translated = new byte[length];
            output.readBytes(translated);
            assertArrayEquals(sourceChunk, translated);
        } finally {
            output.release();
            input.release();
            chunkData.release();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 7, 31})
    void chunkCopiesValidGlobalBiomePaletteUnchanged(int bits) {
        ByteBuf chunkData = Unpooled.buffer();
        chunkData.writeShort(0);
        writeSingletonBlockPalette(chunkData, 0);
        writeGlobalBiomePalette(chunkData, bits);
        byte[] sourceChunk = bytesOf(chunkData);
        ByteBuf input = chunkPacket(new byte[] {10, 0}, chunkData, new byte[] {0});
        ByteBuf output = translator.translate(input, ALLOCATOR);
        try {
            readVarInt(output);
            output.skipBytes(8 + 2);
            int length = readVarInt(output);
            byte[] translated = new byte[length];
            output.readBytes(translated);
            assertArrayEquals(sourceChunk, translated);
        } finally {
            output.release();
            input.release();
            chunkData.release();
        }
    }

    @Test
    void chunkRejectsUnsupportedGlobalBiomeWidth() {
        ByteBuf chunkData = Unpooled.buffer();
        chunkData.writeShort(0);
        writeSingletonBlockPalette(chunkData, 0);
        writeGlobalBiomePalette(chunkData, 32);
        assertMalformedChunk(new byte[] {10, 0}, chunkData);
    }

    @Test
    void chunkRejectsMalformedNbtLengthsPalettesAndPackedIndexes() {
        assertMalformedChunk(new byte[] {0}, validSingletonSection());
        assertMalformedChunk(new byte[] {10, 10, 0, 1, 'x'}, validSingletonSection());

        ByteBuf invalidBits = Unpooled.buffer();
        invalidBits.writeShort(0).writeByte(3);
        assertMalformedChunk(new byte[] {10, 0}, invalidBits);

        ByteBuf singletonData = Unpooled.buffer();
        singletonData.writeShort(0).writeByte(0);
        writeVarInt(singletonData, 0);
        writeVarInt(singletonData, 1);
        assertMalformedChunk(new byte[] {10, 0}, singletonData);

        ByteBuf excessivePalette = Unpooled.buffer();
        excessivePalette.writeShort(0).writeByte(4);
        writeVarInt(excessivePalette, 17);
        assertMalformedChunk(new byte[] {10, 0}, excessivePalette);

        ByteBuf invalidIndex = Unpooled.buffer();
        invalidIndex.writeShort(0).writeByte(4);
        writeVarInt(invalidIndex, 1);
        writeVarInt(invalidIndex, 0);
        long[] storage = new long[packedLongCount(4_096, 4)];
        pack(storage, 4, 0, 1);
        writeVarInt(invalidIndex, storage.length);
        for (long value : storage) {
            invalidIndex.writeLong(value);
        }
        writeSingletonBiome(invalidIndex, 0);
        assertMalformedChunk(new byte[] {10, 0}, invalidIndex);

        ByteBuf wrongGlobalLength = Unpooled.buffer();
        wrongGlobalLength.writeShort(0).writeByte(profile.sourceGlobalPaletteBits());
        writeVarInt(wrongGlobalLength, 0);
        assertMalformedChunk(new byte[] {10, 0}, wrongGlobalLength);

        ByteBuf outOfRangeGlobal = Unpooled.buffer();
        outOfRangeGlobal.writeShort(0).writeByte(profile.sourceGlobalPaletteBits());
        int longCount = packedLongCount(4_096, profile.sourceGlobalPaletteBits());
        writeVarInt(outOfRangeGlobal, longCount);
        long[] global = new long[longCount];
        pack(global, profile.sourceGlobalPaletteBits(), 0, profile.sourceStateCount());
        for (long value : global) {
            outOfRangeGlobal.writeLong(value);
        }
        writeSingletonBiome(outOfRangeGlobal, 0);
        assertMalformedChunk(new byte[] {10, 0}, outOfRangeGlobal);
    }

    @Test
    void chunkRejectsZeroTruncatedOversizedAndTooManySections() {
        ByteBuf zero = Unpooled.buffer();
        writeVarInt(zero, profile.levelChunkWithLightPacketId());
        zero.writeInt(0).writeInt(0).writeByte(10).writeByte(0);
        writeVarInt(zero, 0);
        assertPacketRejected(zero);

        ByteBuf truncated = Unpooled.buffer();
        writeVarInt(truncated, profile.levelChunkWithLightPacketId());
        truncated.writeInt(0).writeInt(0).writeByte(10).writeByte(0);
        writeVarInt(truncated, 20);
        truncated.writeByte(0);
        assertPacketRejected(truncated);

        ByteBuf oversized = Unpooled.buffer();
        writeVarInt(oversized, profile.levelChunkWithLightPacketId());
        oversized.writeInt(0).writeInt(0).writeByte(10).writeByte(0);
        writeVarInt(oversized, 2_097_153);
        assertPacketRejected(oversized);

        ByteBuf sections = Unpooled.buffer();
        for (int index = 0; index < 65; index++) {
            writeSingletonSection(sections, 0, 0);
        }
        assertMalformedChunk(new byte[] {10, 0}, sections);
    }

    @Test
    void rejectsUnknownAndNonCanonicalPacketIdsWithoutChangingInput() {
        ByteBuf unknown = Unpooled.buffer();
        writeVarInt(unknown, 127);
        int readerIndex = unknown.readerIndex();
        assertThrows(IllegalArgumentException.class,
                () -> translator.translate(unknown, ALLOCATOR));
        assertEquals(readerIndex, unknown.readerIndex());
        assertEquals(1, unknown.refCnt());
        unknown.release();

        ByteBuf nonCanonical = Unpooled.wrappedBuffer(new byte[] {(byte) 0x89, 0});
        assertPacketRejected(nonCanonical);
    }

    @Test
    void setEntityDataIsDeliberatelyExcludedFromTheFailSafeRewriteGate() {
        assertFalse(profile.rewritesPacketId(profile.setEntityDataPacketId()));
        assertFalse(profile.rewrittenPacketIds().contains(profile.setEntityDataPacketId()));

        ByteBuf input = Unpooled.buffer();
        writeVarInt(input, profile.setEntityDataPacketId());
        writeVarInt(input, 42);
        input.writeByte(0xFF);
        int readerIndex = input.readerIndex();
        int referenceCount = input.refCnt();
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> translator.translate(input, ALLOCATOR));
            assertEquals(readerIndex, input.readerIndex());
            assertEquals(referenceCount, input.refCnt());
        } finally {
            input.release();
        }
    }

    private static ByteBuf levelEvent(int eventId, int data, int global) {
        ByteBuf packet = Unpooled.buffer();
        writeVarInt(packet, profile.levelEventPacketId());
        packet.writeInt(eventId).writeLong(123L).writeInt(data).writeByte(global);
        return packet;
    }

    private static ByteBuf blockParticle(int particleType, int state, int booleanValue) {
        ByteBuf packet = Unpooled.buffer();
        writeVarInt(packet, profile.levelParticlesPacketId());
        packet.writeByte(booleanValue);
        packet.writeZero(44);
        writeVarInt(packet, particleType);
        writeVarInt(packet, state);
        return packet;
    }

    private static ByteBuf addEntity(int entityType, int data) {
        ByteBuf packet = Unpooled.buffer();
        writeVarInt(packet, profile.addEntityPacketId());
        writeVarInt(packet, 77);
        packet.writeLong(1L).writeLong(2L);
        writeVarInt(packet, entityType);
        packet.writeDouble(1.5).writeDouble(2.5).writeDouble(3.5);
        packet.writeByte(4).writeByte(5).writeByte(6);
        writeVarInt(packet, data);
        packet.writeShort(11).writeShort(12).writeShort(13);
        return packet;
    }

    private static ByteBuf chunkPacket(byte[] heightmaps, ByteBuf chunkData, byte[] tail) {
        ByteBuf packet = Unpooled.buffer();
        writeVarInt(packet, profile.levelChunkWithLightPacketId());
        packet.writeInt(17).writeInt(-23).writeBytes(heightmaps);
        writeVarInt(packet, chunkData.readableBytes());
        packet.writeBytes(chunkData, chunkData.readerIndex(), chunkData.readableBytes());
        packet.writeBytes(tail);
        return packet;
    }

    private static void writeSingletonSection(ByteBuf output, int state, int biome) {
        output.writeShort(state == 0 ? 0 : 1);
        writeSingletonBlockPalette(output, state);
        writeSingletonBiome(output, biome);
    }

    private static ByteBuf validSingletonSection() {
        ByteBuf section = Unpooled.buffer();
        writeSingletonSection(section, 0, 0);
        return section;
    }

    private static void writeSingletonBlockPalette(ByteBuf output, int state) {
        output.writeByte(0);
        writeVarInt(output, state);
        writeVarInt(output, 0);
    }

    private static void writeSingletonBiome(ByteBuf output, int biome) {
        output.writeByte(0);
        writeVarInt(output, biome);
        writeVarInt(output, 0);
    }

    private static void assertSingletonBiome(ByteBuf input, int biome) {
        assertEquals(0, input.readUnsignedByte());
        assertEquals(biome, readVarInt(input));
        assertEquals(0, readVarInt(input));
    }

    private static void writeLocalSection(
            ByteBuf output, int[] palette, long[] storage, int biome) {
        output.writeShort(2).writeByte(4);
        writeVarInt(output, palette.length);
        for (int state : palette) {
            writeVarInt(output, state);
        }
        writeVarInt(output, storage.length);
        for (long value : storage) {
            output.writeLong(value);
        }
        writeSingletonBiome(output, biome);
    }

    private static void writeGlobalSection(ByteBuf output, long[] storage, int biome) {
        output.writeShort(3).writeByte(profile.sourceGlobalPaletteBits());
        writeVarInt(output, storage.length);
        for (long value : storage) {
            output.writeLong(value);
        }
        writeSingletonBiome(output, biome);
    }

    private static void writeLocalBiomePalette(ByteBuf output) {
        output.writeByte(1);
        writeVarInt(output, 2);
        writeVarInt(output, 3);
        writeVarInt(output, 7);
        long[] storage = new long[packedLongCount(64, 1)];
        for (int index = 0; index < 64; index++) {
            pack(storage, 1, index, index & 1);
        }
        writeVarInt(output, storage.length);
        for (long value : storage) {
            output.writeLong(value);
        }
    }

    private static void writeGlobalBiomePalette(ByteBuf output, int bits) {
        output.writeByte(bits);
        long[] storage = new long[packedLongCount(64, bits)];
        for (int index = 0; index < 64; index++) {
            pack(storage, bits, index, index & ((1L << Math.min(bits, 6)) - 1L));
        }
        writeVarInt(output, storage.length);
        for (long value : storage) {
            output.writeLong(value);
        }
    }

    private static byte[] reviewedHeightmapsNbt() {
        ByteBuf nbt = Unpooled.buffer();
        nbt.writeByte(10);
        nbt.writeByte(12);
        writeNbtString(nbt, "MOTION_BLOCKING");
        nbt.writeInt(2).writeLong(1L).writeLong(2L);
        nbt.writeByte(9);
        writeNbtString(nbt, "review");
        nbt.writeByte(3).writeInt(2).writeInt(11).writeInt(12);
        nbt.writeByte(0);
        byte[] bytes = bytesOf(nbt);
        nbt.release();
        return bytes;
    }

    private static void writeNbtString(ByteBuf output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length).writeBytes(bytes);
    }

    private static void assertMalformedChunk(byte[] heightmaps, ByteBuf data) {
        try {
            ByteBuf input = chunkPacket(heightmaps, data, new byte[0]);
            assertPacketRejected(input);
        } finally {
            data.release();
        }
    }

    private static void assertPacketRejected(ByteBuf packet) {
        int readerIndex = packet.readerIndex();
        int referenceCount = packet.refCnt();
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> translator.translate(packet, ALLOCATOR));
            assertEquals(readerIndex, packet.readerIndex());
            assertEquals(referenceCount, packet.refCnt());
        } finally {
            packet.release();
        }
    }

    private static ByteBuf bytes(int... values) {
        ByteBuf buffer = Unpooled.buffer(values.length);
        for (int value : values) {
            buffer.writeByte(value);
        }
        return buffer;
    }

    private static byte[] bytesOf(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static long[] packValues(int[] values, int bits) {
        long[] packed = new long[packedLongCount(values.length, bits)];
        for (int index = 0; index < values.length; index++) {
            pack(packed, bits, index, values[index]);
        }
        return packed;
    }

    private static void pack(long[] storage, int bits, int index, long value) {
        int valuesPerLong = 64 / bits;
        int longIndex = index / valuesPerLong;
        int bitIndex = index % valuesPerLong * bits;
        storage[longIndex] |= value << bitIndex;
    }

    private static long unpack(long[] storage, int bits, int index) {
        int valuesPerLong = 64 / bits;
        int longIndex = index / valuesPerLong;
        int bitIndex = index % valuesPerLong * bits;
        return storage[longIndex] >>> bitIndex & (1L << bits) - 1L;
    }

    private static int packedLongCount(int values, int bits) {
        int valuesPerLong = 64 / bits;
        return (values + valuesPerLong - 1) / valuesPerLong;
    }

    private static void writeVarInt(ByteBuf output, int value) {
        int remaining = value;
        do {
            int current = remaining & 0x7F;
            remaining >>>= 7;
            if (remaining != 0) {
                current |= 0x80;
            }
            output.writeByte(current);
        } while (remaining != 0);
    }

    private static int readVarInt(ByteBuf input) {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            int current = input.readUnsignedByte();
            value |= (current & 0x7F) << index * 7;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new AssertionError("invalid VarInt in translated test packet");
    }

    private static void writeVarLong(ByteBuf output, long value) {
        long remaining = value;
        do {
            int current = (int) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining != 0) {
                current |= 0x80;
            }
            output.writeByte(current);
        } while (remaining != 0);
    }

    private static long readVarLong(ByteBuf input) {
        long value = 0;
        for (int index = 0; index < 9; index++) {
            int current = input.readUnsignedByte();
            value |= (long) (current & 0x7F) << index * 7;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new AssertionError("invalid VarLong in translated test packet");
    }
}
