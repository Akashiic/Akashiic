package br.com.atmbrasil.protocolobelisk.fixture;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Canonical, external-sort evidence for a dense runtime BlockState descriptor set.
 *
 * <p>Runtime numeric IDs are deliberately excluded from this identity. The exact
 * vanilla-to-runtime IDs that ProtocolObelisk consumes remain authenticated by
 * the independent POBS map.</p>
 */
final class CanonicalBlockStateDescriptorSet {
    static final String CANONICALIZATION = "strip-runtime-id-sort-utf8-lf-v1";
    static final int VANILLA_STATE_COUNT = 26_684;
    static final String PROFILE_ID = "atm10-normal-8.1-neoforge-21.1.249";
    static final String FULL_CLIENT_CONTRACT_SHA256 =
            "9d06683c97b68f2bf5093c15d35a95ab14e8dbde67c6c9e65509cdaebed607a3";
    static final String VANILLA_STATE_TABLE_SHA256 =
            "de9980fae71fecd0112d77cd1c056815dca3a1e1125289efd08b1c0696f499bb";

    private static final int CHUNK_DESCRIPTORS = 100_000;
    private static final int IO_BUFFER_BYTES = 128 * 1024;
    private static final byte[] LF = {'\n'};
    private static final byte[] MAGIC = {'P', 'O', 'B', 'S'};
    private static final int POBS_VERSION = 1;

    private CanonicalBlockStateDescriptorSet() {
    }

    static Evidence analyze(Path runtimeOrderedTable, Path temporaryParent) throws IOException {
        if (!Files.isRegularFile(runtimeOrderedTable)) {
            throw new IllegalArgumentException(
                    "runtime BlockState table is not a regular file: " + runtimeOrderedTable);
        }
        requireCanonicalLineEndings(runtimeOrderedTable);
        Files.createDirectories(temporaryParent);
        Path temporary = Files.createTempDirectory(temporaryParent, ".descriptor-sort-");
        try {
            List<Path> chunks = splitAndSort(runtimeOrderedTable, temporary);
            return mergeAndHash(chunks);
        } finally {
            deleteTree(temporary);
        }
    }

    private static void requireCanonicalLineEndings(Path source) throws IOException {
        int last = -1;
        long bytes = 0;
        try (var input = Files.newInputStream(source)) {
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    int value = Byte.toUnsignedInt(buffer[index]);
                    if (value == '\r') {
                        throw new IllegalArgumentException(
                                "CR is forbidden in the runtime BlockState table");
                    }
                    last = value;
                }
                bytes = Math.addExact(bytes, read);
            }
        }
        if (bytes == 0 || last != '\n') {
            throw new IllegalArgumentException(
                    "runtime BlockState table must be non-empty and LF-terminated");
        }
    }

    private static List<Path> splitAndSort(Path source, Path temporary) throws IOException {
        List<Path> chunks = new ArrayList<>();
        ArrayList<String> descriptors = new ArrayList<>(CHUNK_DESCRIPTORS);
        int expectedRuntimeId = 0;
        try (BufferedReader reader = utf8Reader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.indexOf('\t', tab + 1) >= 0) {
                    throw new IllegalArgumentException(
                            "runtime BlockState line is not two-field TSV at id "
                                    + expectedRuntimeId);
                }
                int runtimeId = parseCanonicalInt(line.substring(0, tab));
                if (runtimeId != expectedRuntimeId) {
                    throw new IllegalArgumentException(
                            "runtime BlockState IDs are not dense at " + expectedRuntimeId);
                }
                String descriptor = line.substring(tab + 1);
                requireCanonicalAsciiDescriptor(descriptor, runtimeId);
                descriptors.add(descriptor);
                expectedRuntimeId++;
                if (descriptors.size() == CHUNK_DESCRIPTORS) {
                    chunks.add(writeSortedChunk(descriptors, temporary, chunks.size()));
                    descriptors.clear();
                }
            }
        }
        if (expectedRuntimeId == 0) {
            throw new IllegalArgumentException("runtime BlockState table is empty");
        }
        if (!descriptors.isEmpty()) {
            chunks.add(writeSortedChunk(descriptors, temporary, chunks.size()));
        }
        return List.copyOf(chunks);
    }

    private static Path writeSortedChunk(
            List<String> descriptors,
            Path temporary,
            int index
    ) throws IOException {
        descriptors.sort(Comparator.naturalOrder());
        String previous = null;
        Path output = temporary.resolve("chunk-%05d.tsv".formatted(index));
        try (BufferedWriter writer = utf8Writer(output)) {
            for (String descriptor : descriptors) {
                if (descriptor.equals(previous)) {
                    throw new IllegalArgumentException(
                            "duplicate BlockState descriptor " + descriptor);
                }
                writer.write(descriptor);
                writer.write('\n');
                previous = descriptor;
            }
        }
        return output;
    }

    private static Evidence mergeAndHash(List<Path> chunks) throws IOException {
        ArrayList<ChunkCursor> cursors = new ArrayList<>(chunks.size());
        PriorityQueue<ChunkCursor> queue = new PriorityQueue<>(
                Comparator.comparing(ChunkCursor::descriptor)
                        .thenComparingInt(ChunkCursor::index));
        try {
            for (int index = 0; index < chunks.size(); index++) {
                ChunkCursor cursor = new ChunkCursor(index, utf8Reader(chunks.get(index)));
                cursors.add(cursor);
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
            MessageDigest digest = sha256Digest();
            int count = 0;
            String previous = null;
            while (!queue.isEmpty()) {
                ChunkCursor cursor = queue.remove();
                String descriptor = cursor.descriptor();
                if (descriptor.equals(previous)) {
                    throw new IllegalArgumentException(
                            "duplicate BlockState descriptor " + descriptor);
                }
                digest.update(descriptor.getBytes(StandardCharsets.US_ASCII));
                digest.update(LF);
                count = Math.incrementExact(count);
                previous = descriptor;
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
            return new Evidence(
                    count,
                    CANONICALIZATION,
                    HexFormat.of().formatHex(digest.digest()));
        } finally {
            IOException closeFailure = null;
            for (ChunkCursor cursor : cursors) {
                try {
                    cursor.close();
                } catch (IOException exception) {
                    if (closeFailure == null) {
                        closeFailure = exception;
                    } else {
                        closeFailure.addSuppressed(exception);
                    }
                }
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    static MapEvidence inspectPobs(byte[] bytes) {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        for (byte expected : MAGIC) {
            if (!input.hasRemaining() || input.get() != expected) {
                throw new IllegalArgumentException("POBS magic mismatch");
            }
        }
        if (!input.hasRemaining() || Byte.toUnsignedInt(input.get()) != POBS_VERSION) {
            throw new IllegalArgumentException("POBS format mismatch");
        }
        if (input.remaining() < Integer.BYTES * 2) {
            throw new IllegalArgumentException("POBS header is truncated");
        }
        int sourceCount = input.getInt();
        int targetCount = input.getInt();
        if (sourceCount != VANILLA_STATE_COUNT || targetCount <= sourceCount) {
            throw new IllegalArgumentException("POBS header count mismatch");
        }
        Set<Integer> uniqueTargets = new HashSet<>(sourceCount * 2);
        int maximumTarget = -1;
        int previousTarget = -1;
        boolean strictlyIncreasing = true;
        for (int sourceId = 0; sourceId < sourceCount; sourceId++) {
            int target = readCanonicalVarInt(input);
            if (target < 0 || target >= targetCount || !uniqueTargets.add(target)) {
                throw new IllegalArgumentException("POBS targets are not unique and bounded");
            }
            maximumTarget = Math.max(maximumTarget, target);
            if (target <= previousTarget) {
                strictlyIncreasing = false;
            }
            previousTarget = target;
        }
        if (input.hasRemaining()) {
            throw new IllegalArgumentException("POBS has trailing bytes");
        }
        return new MapEvidence(
                sourceCount,
                targetCount,
                maximumTarget,
                strictlyIncreasing,
                bytes.length,
                sha256(bytes));
    }

    static byte[] reviewedManifest(Evidence descriptors, MapEvidence map) {
        if (descriptors.count() != map.targetGlobalCount()) {
            throw new IllegalArgumentException(
                    "descriptor-set count differs from the POBS target-global count");
        }
        String manifest = new StringBuilder()
                .append("format-version=2\n")
                .append("profile-id=").append(PROFILE_ID).append('\n')
                .append("minecraft-version=1.21.1\n")
                .append("minecraft-protocol=767\n")
                .append("client-full-contract-sha256=")
                .append(FULL_CLIENT_CONTRACT_SHA256).append('\n')
                .append("vanilla-state-table.count=").append(VANILLA_STATE_COUNT).append('\n')
                .append("vanilla-state-table.sha256=")
                .append(VANILLA_STATE_TABLE_SHA256).append('\n')
                .append("client-global-state.count=").append(map.targetGlobalCount()).append('\n')
                .append("client-state-descriptor-set.count=").append(descriptors.count()).append('\n')
                .append("client-state-descriptor-set.canonicalization=")
                .append(descriptors.canonicalization()).append('\n')
                .append("client-state-descriptor-set.sha256=")
                .append(descriptors.sha256()).append('\n')
                .append("map.file=block-state-map.bin\n")
                .append("map.bytes=").append(map.bytes()).append('\n')
                .append("map.sha256=").append(map.sha256()).append('\n')
                .append("map.count=").append(map.sourceCount()).append('\n')
                .append("map.maximum-target-id=").append(map.maximumTargetId()).append('\n')
                .append("mapping.target-ids-strictly-increasing=")
                .append(map.targetIdsStrictlyIncreasing()).append('\n')
                .append("source-global-palette.bits=")
                .append(ceilLog2(map.sourceCount())).append('\n')
                .append("target-global-palette.bits=")
                .append(ceilLog2(map.targetGlobalCount())).append('\n')
                .append("rewrite-capabilities=BLOCK_UPDATE,CHUNK_BLOCK_STATES,")
                .append("BLOCK_LEVEL_EVENT,SECTION_BLOCKS_UPDATE\n")
                .append("packet.block-update=9\n")
                .append("packet.level-chunk-with-light=39\n")
                .append("packet.level-event=40\n")
                .append("packet.section-blocks-update=73\n")
                .toString();
        return manifest.getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    static void writeAtomically(Path output, byte[] bytes) throws IOException {
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("output requires a parent directory");
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, ".manifest-", ".tmp");
        boolean published = false;
        try {
            Files.write(staging, bytes);
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private static BufferedReader utf8Reader(Path path) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), decoder), IO_BUFFER_BYTES);
    }

    private static BufferedWriter utf8Writer(Path path) throws IOException {
        return new BufferedWriter(
                Files.newBufferedWriter(path, StandardCharsets.UTF_8), IO_BUFFER_BYTES);
    }

    private static int parseCanonicalInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || !Integer.toString(parsed).equals(value)) {
                throw new IllegalArgumentException("non-canonical runtime BlockState ID " + value);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid runtime BlockState ID " + value, exception);
        }
    }

    private static void requireCanonicalAsciiDescriptor(String descriptor, int runtimeId) {
        if (descriptor.isEmpty()) {
            throw new IllegalArgumentException("empty BlockState descriptor at " + runtimeId);
        }
        for (int index = 0; index < descriptor.length(); index++) {
            char value = descriptor.charAt(index);
            if (value < 0x21 || value > 0x7E) {
                throw new IllegalArgumentException(
                        "non-ASCII BlockState descriptor at " + runtimeId);
            }
        }
    }

    private static int readCanonicalVarInt(ByteBuffer input) {
        int result = 0;
        int bytes = 0;
        while (bytes < 5) {
            if (!input.hasRemaining()) {
                throw new IllegalArgumentException("truncated POBS VarInt");
            }
            int current = Byte.toUnsignedInt(input.get());
            result |= (current & 0x7F) << (bytes * 7);
            bytes++;
            if ((current & 0x80) == 0) {
                if (result < 0 || varIntBytes(result) != bytes) {
                    throw new IllegalArgumentException("non-canonical POBS VarInt");
                }
                return result;
            }
        }
        throw new IllegalArgumentException("POBS VarInt exceeds five bytes");
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

    private static int ceilLog2(int value) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    record Evidence(int count, String canonicalization, String sha256) {
        Evidence {
            if (count <= 0
                    || !CANONICALIZATION.equals(canonicalization)
                    || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid canonical descriptor-set evidence");
            }
        }
    }

    record MapEvidence(
            int sourceCount,
            int targetGlobalCount,
            int maximumTargetId,
            boolean targetIdsStrictlyIncreasing,
            int bytes,
            String sha256
    ) {
    }

    private static final class ChunkCursor implements AutoCloseable {
        private final int index;
        private final BufferedReader reader;
        private String descriptor;

        private ChunkCursor(int index, BufferedReader reader) {
            this.index = index;
            this.reader = reader;
        }

        private boolean advance() throws IOException {
            descriptor = reader.readLine();
            return descriptor != null;
        }

        private int index() {
            return index;
        }

        private String descriptor() {
            return descriptor;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
