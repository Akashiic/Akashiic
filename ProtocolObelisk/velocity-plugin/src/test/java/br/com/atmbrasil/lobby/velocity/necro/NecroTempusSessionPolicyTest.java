package br.com.atmbrasil.lobby.velocity.necro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.atmbrasil.protocolobelisk.necro.protocol.WindowRateLimiter;
import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class NecroTempusSessionPolicyTest {
    private static final long EPOCH = 0x1020304050607080L;

    @Test
    void evidenceUpgradesInCanonicalOrderWithoutReplacingSessionState() throws Exception {
        Player owner = player();
        WindowRateLimiter limiter = new WindowRateLimiter(64, 524_288);
        Object session = session(owner, "channel-registered", "CHANNEL_REGISTER", limiter);

        assertEquals("UPGRADED", merge(session, "1.3.3", "MOD_LIST"));
        assertEquals("1.3.3", invoke(session, "modVersion"));
        assertEquals("MOD_LIST", enumName(invoke(session, "evidence")));
        assertEquals(EPOCH, invoke(session, "connectionEpoch"));
        assertSame(limiter, invoke(session, "rateLimiter"));

        assertEquals("UPGRADED", merge(session, "hello-build", "HELLO"));
        assertEquals("hello-build", invoke(session, "modVersion"));
        assertEquals("HELLO", enumName(invoke(session, "evidence")));
        assertEquals("UNCHANGED", merge(session, "older-mod-list", "MOD_LIST"));
        assertEquals("hello-build", invoke(session, "modVersion"));
        assertEquals("CONFLICT", merge(session, "conflicting-hello", "HELLO"));
        assertEquals("hello-build", invoke(session, "modVersion"));

        assertTrue((boolean) invoke(session, "belongsTo", new Class<?>[] {Player.class}, owner));
        assertFalse((boolean) invoke(
                session, "belongsTo", new Class<?>[] {Player.class}, player()));
        assertTrue((boolean) invoke(session, "markConfirmationCounted"));
        assertFalse((boolean) invoke(session, "markConfirmationCounted"));
    }

    @Test
    void inFlightBackendExclusivelyOwnsPacketsUntilClearedOrCycleChanges() throws Exception {
        Object session = session(
                player(), "1.3.3", "MOD_LIST", new WindowRateLimiter(64, 524_288));
        assertTrue(authorizes(session, "old", "old"));
        assertFalse(authorizes(session, "target", "old"));

        invoke(session, "beginBackendTransition", new Class<?>[] {String.class}, "target");
        assertEquals("target", invoke(session, "inFlightBackend"));
        assertTrue(authorizes(session, "target", "old"));
        assertFalse(authorizes(session, "old", "old"));

        invoke(session, "clearBackendTransition");
        assertTrue(authorizes(session, "old", "old"));
        assertFalse(authorizes(session, "target", "old"));

        invoke(session, "beginBackendTransition", new Class<?>[] {String.class}, "target");
        invoke(session, "beginBackendCycle");
        assertEquals("", invoke(session, "inFlightBackend"));
        assertTrue(authorizes(session, "target", "target"));
    }

    @Test
    void tabAuthorityRequiresStrictlyIncreasingSequenceAndResetsPerBackendCycle()
            throws Exception {
        Object session = session(
                player(), "1.3.3", "HELLO", new WindowRateLimiter(64, 524_288));
        assertFalse(tabAuthoritative(session, "forbidden"));
        assertTrue(acceptSequence(session, "forbidden", 1L));
        assertTrue(tabAuthoritative(session, "forbidden"));
        assertFalse(acceptSequence(session, "forbidden", 1L));
        assertFalse(acceptSequence(session, "forbidden", 0L));
        assertTrue(acceptSequence(session, "forbidden", 2L));

        assertTrue(acceptSequence(session, "lobby", 1L));
        assertTrue(tabAuthoritative(session, "lobby"));
        assertFalse(tabAuthoritative(session, "forbidden"));

        invoke(session, "beginBackendCycle");
        assertFalse(tabAuthoritative(session, "lobby"));
        assertTrue(acceptSequence(session, "lobby", 1L));
    }

    @Test
    void capabilityAndRegisterReassertionHaveIndependentPerBackendWindows()
            throws Exception {
        Object session = session(
                player(), "1.3.3", "MOD_LIST", new WindowRateLimiter(64, 524_288));

        assertTrue(allowCapabilityRequest(session, "forbidden", 1_000L));
        assertFalse(allowCapabilityRequest(session, "forbidden", 500_001_000L));
        assertTrue(allowCapabilityRequest(session, "forbidden", 1_000_001_000L));
        assertTrue(allowCapabilityRequest(session, "forbidden", 50L));
        assertTrue(allowCapabilityRequest(session, "lobby", 51L));

        assertTrue(shouldAdvertiseCapability(session, "forbidden", false));
        invoke(
                session,
                "markCapabilityAdvertised",
                new Class<?>[] {String.class},
                "forbidden");
        assertFalse(shouldAdvertiseCapability(session, "forbidden", false));
        assertTrue(shouldAdvertiseCapability(session, "lobby", false));
        assertTrue(shouldAdvertiseCapability(session, "forbidden", true));

        long interval = 5_000_000_000L;
        assertTrue(shouldReassert(session, "forbidden", false, 100L, interval));
        invoke(
                session,
                "markChannelAdvertised",
                new Class<?>[] {String.class, long.class},
                "forbidden",
                100L);
        assertFalse(shouldReassert(session, "forbidden", false, 101L, interval));
        assertTrue(shouldReassert(session, "forbidden", false, 100L + interval, interval));
        assertTrue(shouldReassert(session, "lobby", false, 101L, interval));
        assertTrue(shouldReassert(session, "forbidden", true, 101L, interval));
    }

    private static boolean authorizes(Object session, String source, String current)
            throws Exception {
        return (boolean) invoke(
                session,
                "authorizesBackend",
                new Class<?>[] {String.class, String.class},
                source,
                current);
    }

    private static boolean acceptSequence(Object session, String backend, long sequence)
            throws Exception {
        return (boolean) invoke(
                session,
                "acceptTabBridgeSequence",
                new Class<?>[] {String.class, long.class},
                backend,
                sequence);
    }

    private static boolean tabAuthoritative(Object session, String backend) throws Exception {
        return (boolean) invoke(
                session,
                "tabBridgeAuthoritativeFor",
                new Class<?>[] {String.class},
                backend);
    }

    private static boolean allowCapabilityRequest(Object session, String backend, long now)
            throws Exception {
        return (boolean) invoke(
                session,
                "allowCapabilityRequest",
                new Class<?>[] {String.class, long.class},
                backend,
                now);
    }

    private static boolean shouldAdvertiseCapability(
            Object session, String backend, boolean force) throws Exception {
        return (boolean) invoke(
                session,
                "shouldAdvertiseCapability",
                new Class<?>[] {String.class, boolean.class},
                backend,
                force);
    }

    private static boolean shouldReassert(
            Object session,
            String backend,
            boolean force,
            long now,
            long interval) throws Exception {
        return (boolean) invoke(
                session,
                "shouldReassertChannel",
                new Class<?>[] {String.class, boolean.class, long.class, long.class},
                backend,
                force,
                now,
                interval);
    }

    private static String merge(Object session, String version, String evidence)
            throws Exception {
        Object merged = invoke(
                session,
                "mergeEvidence",
                new Class<?>[] {String.class, evidenceType()},
                version,
                enumConstant(evidenceType(), evidence));
        return enumName(merged);
    }

    private static Object session(
            Player owner,
            String version,
            String evidence,
            WindowRateLimiter limiter) throws Exception {
        Class<?> type = nestedType("ClientSession");
        Constructor<?> constructor = type.getDeclaredConstructor(
                Player.class,
                long.class,
                String.class,
                evidenceType(),
                WindowRateLimiter.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                owner, EPOCH, version, enumConstant(evidenceType(), evidence), limiter);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Class<?> evidenceType() {
        return nestedType("ConfirmationEvidence");
    }

    private static Class<?> nestedType(String simpleName) {
        return Arrays.stream(NecroTempusTransportService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow();
    }

    private static Object enumConstant(Class<?> enumType, String name) {
        return Arrays.stream(enumType.getEnumConstants())
                .filter(constant -> enumName(constant).equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String enumName(Object constant) {
        return ((Enum<?>) constant).name();
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("toString")) {
                        return "PlayerProxy";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return arguments != null && arguments.length == 1
                                && proxy == arguments[0];
                    }
                    return null;
                });
    }
}
