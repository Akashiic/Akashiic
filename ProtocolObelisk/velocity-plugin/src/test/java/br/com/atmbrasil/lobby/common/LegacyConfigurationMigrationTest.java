package br.com.atmbrasil.lobby.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LegacyConfigurationMigrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesBytesExactlyAndPreservesTheLegacySource() throws Exception {
        Path legacy = temporaryDirectory.resolve("atm10-lobby-bridge/bridge.properties");
        Path target = temporaryDirectory.resolve("protocolobelisk/bridge.properties");
        byte[] expected = "config-version=4\nenabled=true\n# ç\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, expected);

        var migration = LegacyConfigurationMigration.copyIfTargetMissing(legacy, target);

        assertTrue(migration.isPresent());
        assertEquals(legacy, migration.orElseThrow().legacyConfig());
        assertEquals(target, migration.orElseThrow().targetConfig());
        assertEquals(expected.length, migration.orElseThrow().bytes());
        assertArrayEquals(expected, Files.readAllBytes(legacy));
        assertArrayEquals(expected, Files.readAllBytes(target));
    }

    @Test
    void anExistingTargetAlwaysWinsWithoutRewrite() throws Exception {
        Path legacy = temporaryDirectory.resolve("legacy/config.yml");
        Path target = temporaryDirectory.resolve("new/config.yml");
        Files.createDirectories(legacy.getParent());
        Files.createDirectories(target.getParent());
        Files.writeString(legacy, "legacy", StandardCharsets.UTF_8);
        Files.writeString(target, "current", StandardCharsets.UTF_8);

        assertTrue(LegacyConfigurationMigration.copyIfTargetMissing(legacy, target).isEmpty());
        assertEquals("legacy", Files.readString(legacy, StandardCharsets.UTF_8));
        assertEquals("current", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void absentLegacyConfigurationDoesNotCreateADataDirectory() throws Exception {
        Path legacy = temporaryDirectory.resolve("missing/config.yml");
        Path target = temporaryDirectory.resolve("new/config.yml");

        assertTrue(LegacyConfigurationMigration.copyIfTargetMissing(legacy, target).isEmpty());
        assertFalse(Files.exists(target.getParent()));
    }

    @Test
    void nonRegularLegacyConfigurationFailsClosed() throws Exception {
        Path legacy = temporaryDirectory.resolve("legacy/config.yml");
        Path target = temporaryDirectory.resolve("new/config.yml");
        Files.createDirectories(legacy);

        assertThrows(IOException.class,
                () -> LegacyConfigurationMigration.copyIfTargetMissing(legacy, target));
        assertFalse(Files.exists(target));
    }

    @Test
    void symbolicLinkLegacyConfigurationFailsClosed() throws Exception {
        Path actual = temporaryDirectory.resolve("actual/config.yml");
        Path legacy = temporaryDirectory.resolve("legacy/config.yml");
        Path target = temporaryDirectory.resolve("new/config.yml");
        Files.createDirectories(actual.getParent());
        Files.createDirectories(legacy.getParent());
        Files.writeString(actual, "config-version=4", StandardCharsets.UTF_8);
        Files.createSymbolicLink(legacy, actual);

        assertThrows(IOException.class,
                () -> LegacyConfigurationMigration.copyIfTargetMissing(legacy, target));
        assertFalse(Files.exists(target));
        assertEquals("config-version=4", Files.readString(actual, StandardCharsets.UTF_8));
    }

    @Test
    void oversizedLegacyConfigurationFailsClosed() throws Exception {
        Path legacy = temporaryDirectory.resolve("legacy/config.yml");
        Path target = temporaryDirectory.resolve("new/config.yml");
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, new byte[(int)
                LegacyConfigurationMigration.MAXIMUM_LEGACY_CONFIG_BYTES + 1]);

        assertThrows(IOException.class,
                () -> LegacyConfigurationMigration.copyIfTargetMissing(legacy, target));
        assertFalse(Files.exists(target));
    }

    @Test
    void sourceAndTargetMustBeDifferent() {
        Path config = temporaryDirectory.resolve("config.yml");

        assertThrows(IllegalArgumentException.class,
                () -> LegacyConfigurationMigration.copyIfTargetMissing(config, config));
    }
}
