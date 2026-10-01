package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.plugin.Plugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class PluginMetadataTest {
    @Test
    void velocityMetadataCarriesTheProtocolObeliskIdentityAndReleaseVersion()
            throws IOException {
        try (var stream = PluginMetadataTest.class.getResourceAsStream("/velocity-plugin.json")) {
            assertTrue(stream != null, "velocity-plugin.json must be packaged");
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String expectedVersion = System.getProperty("protocolObeliskVersion");
            assertTrue(expectedVersion != null && !expectedVersion.isBlank(),
                    "the build must provide protocolObeliskVersion");
            assertTrue(metadata.contains("\"id\": \"protocolobelisk\""));
            assertTrue(metadata.contains("\"name\": \"ProtocolObelisk\""));
            assertTrue(metadata.contains("\"version\": \"" + expectedVersion + "\""));
            assertTrue(metadata.contains(
                    "\"main\": \"br.com.atmbrasil.lobby.velocity."
                            + "Atm10LobbyVelocityPlugin\""));
            Plugin annotation = Atm10LobbyVelocityPlugin.class.getAnnotation(Plugin.class);
            assertTrue(annotation != null, "the root entrypoint must retain @Plugin");
            assertEquals(expectedVersion, annotation.version(),
                    "@Plugin and generated velocity-plugin.json must not drift");
        }
    }
}
