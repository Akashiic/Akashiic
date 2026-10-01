package br.com.atmbrasil.lobby.velocity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Atomic and bounded persistence for backend-captured Silent Gear data snapshots. */
final class SilentGearSnapshotStore {
    private static final byte[] MAGIC = "ATMBSG01".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final String FILE_SUFFIX = ".sgsnapshot";
    private static final int MAXIMUM_METADATA_STRING_BYTES = 256;
    private static final int MAXIMUM_ENVELOPE_BYTES = 4_096;

    private final Path directory;
    private final int maximumPayloadBytes;
    private final int maximumTotalPayloadBytes;
    private final int maximumSnapshots;
    private final ConcurrentHashMap<String, SilentGearSnapshot> snapshots =
            new ConcurrentHashMap<>();

    SilentGearSnapshotStore(
            Path directory,
            int maximumPayloadBytes,
            int maximumTotalPayloadBytes,
            int maximumSnapshots) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (maximumPayloadBytes < 1
                || maximumTotalPayloadBytes < maximumPayloadBytes
                || maximumSnapshots < 1) {
            throw new IllegalArgumentException("invalid Silent Gear snapshot store limits");
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
        this.maximumTotalPayloadBytes = maximumTotalPayloadBytes;
        this.maximumSnapshots = maximumSnapshots;
    }

    LoadResult load() throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Silent Gear snapshot location is not a real directory");
        }

        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                directory, "*" + FILE_SUFFIX)) {
            for (Path path : stream) {
                candidates.add(path);
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));

        List<String> warnings = new ArrayList<>();
        LinkedHashMap<String, SilentGearSnapshot> loaded = new LinkedHashMap<>();
        for (Path path : candidates) {
            if (loaded.size() >= maximumSnapshots) {
                warnings.add("snapshot limit omitted " + path.getFileName());
                continue;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                warnings.add("ignored non-regular snapshot " + path.getFileName());
                continue;
            }
            try {
                long maximumFileBytes = Math.addExact(
                        maximumTotalPayloadBytes, MAXIMUM_ENVELOPE_BYTES + DIGEST_BYTES);
                long size = Files.size(path);
                if (size < MAGIC.length + DIGEST_BYTES || size > maximumFileBytes) {
                    throw new IOException("snapshot file size is outside bounds");
                }
                byte[] encoded = Files.readAllBytes(path);
                SilentGearSnapshot snapshot = decode(encoded);
                String expectedName = fileName(snapshot.clientRegistryFingerprint());
                if (!path.getFileName().toString().equals(expectedName)) {
                    throw new IOException("snapshot filename does not match fingerprint");
                }
                loaded.put(snapshot.clientRegistryFingerprint(), snapshot);
            } catch (IOException | IllegalArgumentException exception) {
                warnings.add("ignored invalid snapshot " + path.getFileName()
                        + ": " + stableMessage(exception));
            }
        }
        snapshots.clear();
        snapshots.putAll(loaded);
        return new LoadResult(loaded.size(), warnings);
    }

    Optional<SilentGearSnapshot> find(String clientRegistryFingerprint) {
        return Optional.ofNullable(snapshots.get(clientRegistryFingerprint));
    }

    int size() {
        return snapshots.size();
    }

    StoreResult store(SilentGearSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        validateSnapshotBounds(snapshot);
        SilentGearSnapshot existing = snapshots.get(snapshot.clientRegistryFingerprint());
        if (existing != null && existing.hasSamePayloads(snapshot)) {
            return new StoreResult(false, existing);
        }
        if (existing == null && snapshots.size() >= maximumSnapshots) {
            throw new IOException("Silent Gear snapshot count budget exhausted");
        }

        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Silent Gear snapshot location is not a real directory");
        }
        byte[] encoded = encode(snapshot);
        Path target = directory.resolve(fileName(snapshot.clientRegistryFingerprint()));
        Path temporary = Files.createTempFile(
                directory, ".silentgear-", ".tmp").toAbsolutePath().normalize();
        if (!temporary.getParent().equals(directory)) {
            throw new IOException("temporary snapshot escaped its directory");
        }

        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "atomic snapshot publication is unsupported by this filesystem",
                        exception);
            }
            moved = true;
            forceDirectory(directory);
            snapshots.put(snapshot.clientRegistryFingerprint(), snapshot);
            return new StoreResult(true, snapshot);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private byte[] encode(SilentGearSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(
                snapshot.payloadBytes() + MAXIMUM_ENVELOPE_BYTES);
        try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
            output.write(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, snapshot.clientRegistryFingerprint());
            writeString(output, SilentGearProtocol.NETWORK_VERSION);
            writeString(output, snapshot.sourceServer());
            output.writeLong(snapshot.capturedAtEpochMillis());
            output.writeInt(SilentGearProtocol.SYNC_CHANNELS.size());
            for (String channelId : SilentGearProtocol.SYNC_CHANNELS) {
                byte[] payload = snapshot.payload(channelId);
                writeString(output, channelId);
                output.writeInt(payload.length);
                output.write(hexDigest(payload));
                output.write(payload);
            }
        }
        byte[] body = bodyBytes.toByteArray();
        ByteArrayOutputStream complete = new ByteArrayOutputStream(body.length + DIGEST_BYTES);
        complete.writeBytes(body);
        complete.writeBytes(hexDigest(body));
        return complete.toByteArray();
    }

    private SilentGearSnapshot decode(byte[] encoded) throws IOException {
        if (encoded.length <= DIGEST_BYTES) {
            throw new IOException("truncated snapshot");
        }
        int bodyLength = encoded.length - DIGEST_BYTES;
        byte[] body = Arrays.copyOf(encoded, bodyLength);
        byte[] expectedDigest = Arrays.copyOfRange(encoded, bodyLength, encoded.length);
        if (!MessageDigest.isEqual(expectedDigest, hexDigest(body))) {
            throw new IOException("snapshot envelope digest mismatch");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            byte[] magic = input.readNBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new IOException("snapshot magic mismatch");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("unsupported snapshot format version");
            }
            String fingerprint = readString(input);
            String networkVersion = readString(input);
            if (!networkVersion.equals(SilentGearProtocol.NETWORK_VERSION)) {
                throw new IOException("snapshot Silent Gear network version mismatch");
            }
            String sourceServer = readString(input);
            long capturedAt = input.readLong();
            int payloadCount = input.readInt();
            if (payloadCount != SilentGearProtocol.SYNC_CHANNELS.size()) {
                throw new IOException("snapshot payload count mismatch");
            }

            LinkedHashMap<String, byte[]> payloads = new LinkedHashMap<>();
            int totalPayloadBytes = 0;
            for (String expectedChannel : SilentGearProtocol.SYNC_CHANNELS) {
                String channelId = readString(input);
                if (!channelId.equals(expectedChannel)) {
                    throw new IOException("snapshot channel order mismatch");
                }
                int length = input.readInt();
                if (length < 1 || length > maximumPayloadBytes) {
                    throw new IOException("snapshot payload length is outside bounds");
                }
                totalPayloadBytes = Math.addExact(totalPayloadBytes, length);
                if (totalPayloadBytes > maximumTotalPayloadBytes) {
                    throw new IOException("snapshot total payload bytes exceed bounds");
                }
                byte[] payloadDigest = input.readNBytes(DIGEST_BYTES);
                if (payloadDigest.length != DIGEST_BYTES) {
                    throw new EOFException("truncated snapshot payload digest");
                }
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) {
                    throw new EOFException("truncated snapshot payload");
                }
                if (!MessageDigest.isEqual(payloadDigest, hexDigest(payload))) {
                    throw new IOException("snapshot payload digest mismatch");
                }
                SilentGearProtocol.validateNonEmptyMapPayload(payload, maximumPayloadBytes);
                payloads.put(channelId, payload);
            }
            if (input.available() != 0) {
                throw new IOException("snapshot contains trailing body bytes");
            }
            return new SilentGearSnapshot(
                    fingerprint, sourceServer, capturedAt, payloads);
        } catch (ArithmeticException exception) {
            throw new IOException("snapshot byte count overflow", exception);
        }
    }

    private void validateSnapshotBounds(SilentGearSnapshot snapshot) {
        int totalBytes = 0;
        for (String channelId : SilentGearProtocol.SYNC_CHANNELS) {
            byte[] payload = snapshot.payload(channelId);
            SilentGearProtocol.validateNonEmptyMapPayload(payload, maximumPayloadBytes);
            totalBytes = Math.addExact(totalBytes, payload.length);
        }
        if (totalBytes > maximumTotalPayloadBytes) {
            throw new IllegalArgumentException("Silent Gear snapshot exceeds total byte budget");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length < 1 || encoded.length > MAXIMUM_METADATA_STRING_BYTES) {
            throw new IOException("snapshot metadata string length is outside bounds");
        }
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAXIMUM_METADATA_STRING_BYTES) {
            throw new IOException("snapshot metadata string length is outside bounds");
        }
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) {
            throw new EOFException("truncated snapshot metadata string");
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static byte[] hexDigest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String fileName(String fingerprint) {
        return SilentGearSnapshot.requireFingerprint(fingerprint) + FILE_SUFFIX;
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static String stableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    record LoadResult(int loadedSnapshots, List<String> warnings) {
        LoadResult {
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    record StoreResult(boolean changed, SilentGearSnapshot snapshot) {
        StoreResult {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
