package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class RoutingNonInterferenceTest {
    @Test
    void routingHooksAreUniqueSynchronousAndStrictlyObservational() throws IOException {
        var methods = Arrays.asList(Atm10LobbyVelocityPlugin.class.getDeclaredMethods());
        // The HF2 BUNGEE-TRACE observer captures the event in a lambda; javac emits that as a
        // synthetic method. Only real (subscribable) hooks count toward uniqueness.
        var preConnectHooks = methods.stream()
                .filter(method -> !method.isSynthetic())
                .filter(method -> Arrays.asList(method.getParameterTypes())
                        .contains(ServerPreConnectEvent.class))
                .toList();
        assertEquals(1, preConnectHooks.size());
        var hook = preConnectHooks.getFirst();
        assertEquals("onServerPreConnect", hook.getName());
        Subscribe subscription = hook.getAnnotation(Subscribe.class);
        assertNotNull(subscription);
        assertEquals(Short.MIN_VALUE, subscription.priority());
        assertFalse(subscription.async());

        String pluginSource = Files.readString(Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java"),
                StandardCharsets.UTF_8);
        String pluginHook = methodBody(
                pluginSource,
                "public void onServerPreConnect(ServerPreConnectEvent event)");
        assertTrue(pluginHook.contains("transport.handleServerPreConnect(event)"));
        assertFalse(pluginHook.contains("setResult("));
        assertFalse(pluginHook.contains("requestServer"));
        assertFalse(pluginHook.contains("createConnectionRequest"));

        String transportSource = Files.readString(Path.of(
                "src/main/java/br/com/atmbrasil/lobby/velocity/necro/"
                        + "NecroTempusTransportService.java"),
                StandardCharsets.UTF_8);
        String transportHook = methodBody(
                transportSource,
                "public void handleServerPreConnect(ServerPreConnectEvent event)");
        assertTrue(transportHook.contains("event.getResult().getServer()"));
        assertFalse(transportHook.contains("setResult("));
        assertFalse(transportHook.contains("requestServer"));
        assertFalse(transportHook.contains("createConnectionRequest"));

        assertTrue(methods.stream()
                .filter(method -> method.getName().equals("onInitialServerSelected"))
                .anyMatch(method -> Arrays.stream(method.getParameterTypes())
                        .map(Class::getName)
                        .anyMatch(name -> name.endsWith("PlayerChooseInitialServerEvent"))));
    }

    @Test
    void compiledPluginHasNoConnectionRequestOrInitialServerMutationSymbol()
            throws IOException {
        String resourceName = "/" + Atm10LobbyVelocityPlugin.class.getName()
                .replace('.', '/') + ".class";
        try (var stream = RoutingNonInterferenceTest.class.getResourceAsStream(resourceName)) {
            assertTrue(stream != null, "compiled Velocity plugin class must be available");
            String bytecodeSymbols = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(bytecodeSymbols.contains("getInitialServer"));
            assertFalse(bytecodeSymbols.contains("createConnectionRequest"));
            assertFalse(bytecodeSymbols.contains("setInitialServer"));
            assertFalse(bytecodeSymbols.contains("RoutingPolicy"));
        }
    }

    @Test
    void legacyForgeGuardObservesOnlyAnExistingInFlightConnection() throws IOException {
        String resourceName = "/" + VelocityLegacyForgeHandoffGuard.class.getName()
                .replace('.', '/') + ".class";
        try (var stream = RoutingNonInterferenceTest.class.getResourceAsStream(resourceName)) {
            assertTrue(stream != null, "compiled legacy Forge guard must be available");
            String bytecodeSymbols = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(bytecodeSymbols.contains("getConnectionInFlight"));
            assertFalse(bytecodeSymbols.contains("createConnectionRequest"));
            assertFalse(bytecodeSymbols.contains("setConnectedServer"));
            assertFalse(bytecodeSymbols.contains("setInitialServer"));
            assertFalse(bytecodeSymbols.contains("ServerPreConnectEvent"));
        }
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method signature must exist: " + signature);
        int nextSubscription = source.indexOf("\n    @Subscribe", start + signature.length());
        int end = nextSubscription >= 0 ? nextSubscription : source.length();
        return source.substring(start, end);
    }
}
