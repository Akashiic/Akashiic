package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.lobby.velocity.protocol.NeoForgeHandshakeCodec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Ae2JeiSessionOptimizationTest {
    @Test
    void loadsExactReviewedConfigWithFacadeRemovalDisabled() throws Exception {
        Ae2JeiSessionOptimization optimization = Ae2JeiSessionOptimization.loadReviewed(
                getClass().getClassLoader());

        assertEquals(756, optimization.contentBytes());
        assertEquals(
                "6ee20273f31bf40a34fb7ed3136c1b03b744855a31fa719e3ef46c7f2b77de87",
                optimization.contentSha256());
        String toml = new String(optimization.contents(), StandardCharsets.UTF_8);
        assertTrue(toml.contains("[recipeViewers]"));
        assertTrue(toml.contains("enableFacadesInRecipeViewer = true"));
        assertFalse(toml.contains("enableFacadesInRecipeViewer = false"));
        assertTrue(toml.contains("maxCellContentShown = 5"));
    }

    @Test
    void returnsDefensiveContentCopies() throws Exception {
        Ae2JeiSessionOptimization optimization = Ae2JeiSessionOptimization.loadReviewed(
                getClass().getClassLoader());
        byte[] first = optimization.contents();
        first[0] ^= 0x7f;
        assertNotEquals(first[0], optimization.contents()[0]);
    }

    @Test
    void encodesTheReviewedConfigAsAnExactNeoForgePayload() throws Exception {
        Ae2JeiSessionOptimization optimization = Ae2JeiSessionOptimization.loadReviewed(
                getClass().getClassLoader());

        byte[] payload = NeoForgeHandshakeCodec.encodeConfigFilePayload(
                Ae2JeiSessionOptimization.CONFIG_FILE_NAME,
                optimization.contents(),
                774);

        assertEquals(774, payload.length);
        assertEquals(15, Byte.toUnsignedInt(payload[0]));
        assertEquals(0xf4, Byte.toUnsignedInt(payload[16]));
        assertEquals(0x05, Byte.toUnsignedInt(payload[17]));
        assertArrayEquals(optimization.contents(), Arrays.copyOfRange(payload, 18, payload.length));
    }

    @Test
    void selectsOnlyTheExactProfileWithAe2Advertised() throws Exception {
        Ae2JeiSessionOptimization optimization = Ae2JeiSessionOptimization.loadReviewed(
                getClass().getClassLoader());
        SilentGearEmbeddedProfile profile = SilentGearEmbeddedProfile.loadReviewed(
                getClass().getClassLoader(), 767, 1_048_576, 3_145_728);

        assertTrue(optimization.matches(profile, Set.of("ae2", "silentgear")));
        assertFalse(optimization.matches(profile, Set.of("silentgear")));
        assertFalse(optimization.matches(null, Set.of("ae2")));
    }

    @Test
    void rejectsAChangedEmbeddedConfig() {
        ClassLoader corruptingLoader = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new ByteArrayInputStream(new byte[756]);
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> Ae2JeiSessionOptimization.loadReviewed(corruptingLoader));
    }
}
