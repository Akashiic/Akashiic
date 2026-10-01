package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class PluginMetadataTest {
    @Test
    void paperMetadataCarriesTheProtocolObeliskIdentityAndLegacyCompatibility()
            throws IOException {
        try (var stream = PluginMetadataTest.class.getResourceAsStream("/plugin.yml")) {
            assertTrue(stream != null, "plugin.yml must be packaged");
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String expectedVersion = System.getProperty("protocolObeliskVersion");
            assertTrue(expectedVersion != null && !expectedVersion.isBlank(),
                    "the build must provide protocolObeliskVersion");
            assertEquals("1.9.16-EVOLUTION", expectedVersion,
                    "the Paper module must be bound to the Evolution release");
            assertTrue(metadata.contains("name: ProtocolObelisk"));
            assertTrue(metadata.contains("version: " + expectedVersion));
            assertTrue(metadata.contains("provides: [Atm10LobbyBridge]"));
            assertTrue(metadata.contains("softdepend: [TAB]"));
            assertTrue(!metadata.contains("\ndepend: [TAB]"));
            assertTrue(metadata.indexOf("\nmain:") == metadata.lastIndexOf("\nmain:"),
                    "the unified JAR must expose exactly one JavaPlugin entrypoint");
            assertTrue(!metadata.contains("ProtocolObeliskNecroTempus"),
                    "the absorbed companion must not retain standalone metadata");
            int commandsStart = metadata.indexOf("\ncommands:");
            int permissionsStart = metadata.indexOf("\npermissions:");
            assertTrue(commandsStart >= 0 && permissionsStart > commandsStart,
                    "plugin.yml must contain bounded command metadata");
            String commands = metadata.substring(commandsStart, permissionsStart);
            assertFalse(commands.contains("atm10lobby"),
                    "the administrative command must not capture the legacy transfer label");
            assertFalse(commands.contains("aliases:"),
                    "the administrative command must remain canonical-only");
            assertTrue(metadata.contains("permission: protocolobelisk.admin"));
            assertTrue(!metadata.contains("  atm:"));
        }
    }
}
