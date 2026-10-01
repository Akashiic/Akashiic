package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class VelocityPluginMessageLimitsTest {
    @Test
    void defaultsMatchVelocityFourDirectionSpecificLimits() {
        VelocityPluginMessageLimits.Limits limits =
                VelocityPluginMessageLimits.inspect(ignored -> null);

        assertEquals(32_767, limits.serverboundBytes());
        assertEquals(1_048_576, limits.clientboundBytes());
        assertFalse(limits.sharedOverride());
    }

    @Test
    void directionSpecificLimitsKeepClientTrafficNarrowerThanTrustedBackendTraffic() {
        Map<String, String> properties = Map.of(
                VelocityPluginMessageLimits.SERVERBOUND_PROPERTY, "1048576",
                VelocityPluginMessageLimits.CLIENTBOUND_PROPERTY, "8388602");

        VelocityPluginMessageLimits.Limits limits =
                VelocityPluginMessageLimits.inspect(properties::get);

        assertEquals(1_048_576, limits.serverboundBytes());
        assertEquals(8_388_602, limits.clientboundBytes());
        assertFalse(limits.sharedOverride());
        assertEquals(
                "-Dvelocity.max-plugin-message-payload-size.serverbound=1048576 "
                        + "-Dvelocity.max-plugin-message-payload-size.clientbound=8388602",
                VelocityPluginMessageLimits.recommendedArguments(
                        limits.serverboundBytes(), limits.clientboundBytes()));
    }

    @Test
    void sharedOverrideTakesPrecedenceExactlyAsVelocityDoes() {
        Map<String, String> properties = Map.of(
                VelocityPluginMessageLimits.ALL_DIRECTIONS_PROPERTY, "1048576",
                VelocityPluginMessageLimits.SERVERBOUND_PROPERTY, "123456",
                VelocityPluginMessageLimits.CLIENTBOUND_PROPERTY, "8388602");

        VelocityPluginMessageLimits.Limits limits =
                VelocityPluginMessageLimits.inspect(properties::get);

        assertEquals(1_048_576, limits.serverboundBytes());
        assertEquals(1_048_576, limits.clientboundBytes());
        assertTrue(limits.sharedOverride());
    }

    @Test
    void malformedOrNonPositiveLimitsFailClosed() {
        for (String invalid : new String[] {"0", "-1", "not-a-number", "2147483648"}) {
            Map<String, String> properties = Map.of(
                    VelocityPluginMessageLimits.CLIENTBOUND_PROPERTY, invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> VelocityPluginMessageLimits.inspect(properties::get));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> VelocityPluginMessageLimits.recommendedArguments(1_048_576, 0));
    }
}
