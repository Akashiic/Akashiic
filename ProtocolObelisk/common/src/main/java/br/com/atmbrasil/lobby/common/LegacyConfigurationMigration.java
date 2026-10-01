package br.com.atmbrasil.lobby.common;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** Safely copies one legacy configuration into a rebranded plugin data directory. */
public final class LegacyConfigurationMigration {
    public static final long MAXIMUM_LEGACY_CONFIG_BYTES = 1_048_576L;

    private LegacyConfigurationMigration() {
    }

    /**
     * Copies {@code legacyConfig} only when {@code targetConfig} does not exist.
     *
     * <p>The legacy file must be a bounded regular file and may not be a symbolic link. The
     * source is never moved, deleted, or modified. Publication uses a temporary sibling and an
     * atomic move whenever the filesystem supports it, so a failed migration cannot expose a
     * partially copied configuration.</p>
     */
    public static Optional<Migration> copyIfTargetMissing(
            Path legacyConfig,
            Path targetConfig) throws IOException {
        Objects.requireNonNull(legacyConfig, "legacyConfig");
        Objects.requireNonNull(targetConfig, "targetConfig");

        Path normalizedLegacy = legacyConfig.toAbsolutePath().normalize();
        Path normalizedTarget = targetConfig.toAbsolutePath().normalize();
        if (normalizedLegacy.equals(normalizedTarget)) {
            throw new IllegalArgumentException("legacy and target configurations must differ");
        }
        if (Files.exists(targetConfig, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.exists(legacyConfig, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(legacyConfig, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("legacy configuration is not a regular file: " + legacyConfig);
        }

        long sourceBytes = Files.size(legacyConfig);
        if (sourceBytes > MAXIMUM_LEGACY_CONFIG_BYTES) {
            throw new IOException(
                    "legacy configuration exceeds " + MAXIMUM_LEGACY_CONFIG_BYTES + " bytes");
        }
        Path parent = targetConfig.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("target configuration must have a parent");
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(
                parent,
                '.' + targetConfig.getFileName().toString() + '.',
                ".migration");
        boolean published = false;
        try {
            Files.copy(legacyConfig, temporary, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, targetConfig, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, targetConfig);
            }
            published = true;
            return Optional.of(new Migration(legacyConfig, targetConfig, sourceBytes));
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public record Migration(Path legacyConfig, Path targetConfig, long bytes) {
        public Migration {
            Objects.requireNonNull(legacyConfig, "legacyConfig");
            Objects.requireNonNull(targetConfig, "targetConfig");
            if (bytes < 0L || bytes > MAXIMUM_LEGACY_CONFIG_BYTES) {
                throw new IllegalArgumentException("invalid migrated byte count");
            }
        }
    }
}
