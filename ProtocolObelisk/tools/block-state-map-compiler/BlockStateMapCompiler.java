import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Build-only compiler for a reviewed, dense vanilla-to-client BlockState id map.
 *
 * <p>This class is intentionally outside every Gradle source set. It consumes the human-auditable
 * TSV produced by the offline ATM10 ServerFiles comparison, validates all ordering and bounds, and
 * atomically emits the compact runtime resource plus a checksum sidecar.</p>
 */
public final class BlockStateMapCompiler {
    private static final byte[] MAGIC = {'P', 'O', 'B', 'S'};
    private static final int FORMAT_VERSION = 1;
    private static final int EXPECTED_SOURCE_COUNT = 26_684;
    private static final int EXPECTED_TARGET_GLOBAL_COUNT = 1_980_659;
    private static final int MAXIMUM_LINE_BYTES = 16_384;
    private static final Pattern CANONICAL_STATE = Pattern.compile(
            "minecraft:[a-z0-9_./-]+(?:\\[[a-z0-9_./-]+=[^,\\]\\r\\n]+"
                    + "(?:,[a-z0-9_./-]+=[^,\\]\\r\\n]+)*\\])?");

    private BlockStateMapCompiler() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                    "usage: BlockStateMapCompiler <reviewed-map.tsv> <output.bin>");
        }
        Path input = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (input.equals(output)) {
            throw new IllegalArgumentException("input and output must differ");
        }

        byte[] compiled = compile(input);
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("output must have a parent directory");
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
        Path checksum = output.resolveSibling(output.getFileName() + ".sha256");
        Path checksumStaging = Files.createTempFile(
                parent, checksum.getFileName().toString(), ".tmp");
        boolean published = false;
        try {
            Files.write(staging, compiled);
            String hash = sha256(compiled);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    checksumStaging, StandardCharsets.US_ASCII)) {
                writer.write(hash);
                writer.write("  ");
                writer.write(output.getFileName().toString());
                writer.newLine();
            }
            moveAtomically(staging, output);
            moveAtomically(checksumStaging, checksum);
            published = true;
            System.out.printf(
                    "compiled %d mappings, %d bytes, sha256=%s%n",
                    EXPECTED_SOURCE_COUNT, compiled.length, hash);
        } finally {
            if (!published) {
                Files.deleteIfExists(staging);
                Files.deleteIfExists(checksumStaging);
            }
        }
    }

    private static byte[] compile(Path input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(96 * 1024);
        try (DataOutputStream output = new DataOutputStream(bytes);
                BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            output.write(MAGIC);
            output.writeByte(FORMAT_VERSION);
            output.writeInt(EXPECTED_SOURCE_COUNT);
            output.writeInt(EXPECTED_TARGET_GLOBAL_COUNT);

            int expectedSource = 0;
            int previousTarget = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_LINE_BYTES) {
                    throw new IllegalArgumentException("mapping line exceeds byte bound");
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length != 3) {
                    throw new IllegalArgumentException("mapping line must have three TSV fields");
                }
                int source = parseNonNegativeInt(fields[0], "source id");
                int target = parseNonNegativeInt(fields[1], "target id");
                if (source != expectedSource) {
                    throw new IllegalArgumentException(
                            "source ids are not dense at " + expectedSource);
                }
                if (target <= previousTarget || target >= EXPECTED_TARGET_GLOBAL_COUNT) {
                    throw new IllegalArgumentException(
                            "target ids are not strictly increasing and bounded at " + source);
                }
                if (!CANONICAL_STATE.matcher(fields[2]).matches()) {
                    throw new IllegalArgumentException(
                            "invalid canonical state at source id " + source);
                }
                writeVarInt(output, target);
                expectedSource++;
                previousTarget = target;
            }
            if (expectedSource != EXPECTED_SOURCE_COUNT) {
                throw new IllegalArgumentException(
                        "mapping count mismatch: " + expectedSource);
            }
        }
        return bytes.toByteArray();
    }

    private static int parseNonNegativeInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || !Integer.toString(parsed).equals(value)) {
                throw new IllegalArgumentException(field + " is not canonical");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " is invalid", exception);
        }
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            throw new IOException("atomic publication is unavailable for " + target, exception);
        }
    }
}
