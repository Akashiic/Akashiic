package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class PaperSafetyDefaultsTest {
    @Test
    void packagedConfigurationPreservesMenuCompatibilityByDefault() throws IOException {
        assertMenuCompatibilityDefaults("/config.yml");
        assertMenuCompatibilityDefaults("/config-v4.example.yml");
    }

    @Test
    void missingLegacyValueUsesTheDisabledFallbackAndExplicitValuesRemainStrict() {
        String path = "safety.cancel-generic-ui-events";

        assertFalse(Atm10LobbyPaperPlugin.parseOptionalStrictBoolean(null, path, false));
        assertFalse(Atm10LobbyPaperPlugin.parseOptionalStrictBoolean(
                Boolean.FALSE, path, false));
        assertTrue(Atm10LobbyPaperPlugin.parseOptionalStrictBoolean(
                Boolean.TRUE, path, false));
        assertThrows(IllegalArgumentException.class, () ->
                Atm10LobbyPaperPlugin.parseOptionalStrictBoolean("false", path, false));
    }

    private static void assertMenuCompatibilityDefaults(String resource) throws IOException {
        try (var stream = PaperSafetyDefaultsTest.class.getResourceAsStream(resource)) {
            assertTrue(stream != null, resource + " must be packaged");
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertTrue(configuration.isBoolean("safety.cancel-generic-ui-events"),
                    resource + " must provide a boolean generic UI option");
            assertFalse(configuration.getBoolean("safety.cancel-generic-ui-events"),
                    resource + " must keep generic UI cancellation opt-in");
            assertTrue(configuration.isBoolean("safety.clear-inventory-on-join"),
                    resource + " must provide a boolean inventory reset option");
            assertFalse(configuration.getBoolean("safety.clear-inventory-on-join"),
                    resource + " must preserve server-selector items by default");
        }
    }
}
