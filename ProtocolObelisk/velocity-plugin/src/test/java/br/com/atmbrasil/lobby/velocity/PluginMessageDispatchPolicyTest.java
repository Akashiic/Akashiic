package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

final class PluginMessageDispatchPolicyTest {
    @Test
    void protocolCriticalPluginMessagesUseMaximumSynchronousPriority() throws Exception {
        Method method = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "onPluginMessage", PluginMessageEvent.class);
        Subscribe subscription = method.getAnnotation(Subscribe.class);

        assertNotNull(subscription);
        assertEquals(Short.MAX_VALUE, subscription.priority());
        assertFalse(subscription.async());
    }

    @Test
    void silentGearDeadlineIsBoundedButAllowsHeavyFirstJoinDispatch() {
        assertEquals(30_000L, Atm10LobbyVelocityPlugin.SILENT_GEAR_ACK_TIMEOUT_MILLIS);
        assertTrue(Atm10LobbyVelocityPlugin.SILENT_GEAR_ACK_TIMEOUT_MILLIS < 60_000L);
    }
}
