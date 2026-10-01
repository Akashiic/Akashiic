package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Guards the cardinal rule on the first ATM10 client -> Velocity -> Paper lobby hop. */
final class FirstHopCardinalAdmissionRegressionTest {
    private static final Path PLUGIN_SOURCE = Path.of(
            "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java");

    @Test
    void firstHopContainsNoYouerRuntimeControlPlaneOrEvidencePolling() throws Exception {
        String source = Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8);
        for (String forbidden : new String[] {
                "YouerAgentControlServer",
                "YouerAgentControlConfig",
                "YouerAgentManifestRegistry",
                "initializeYouerAgentControl",
                "beginRuntimeBlockStateEvidenceGrace",
                "RuntimeBlockStateGracePollDisposition",
                "runtimeBlockStateGracePollDisposition",
                "RUNTIME_BLOCK_STATE_EVIDENCE_GRACE",
                "acquireBlockStateSelection",
                "EvidenceLease"
        }) {
            assertFalse(source.contains(forbidden),
                    () -> "first hop still references runtime Youer control: " + forbidden);
        }

        assertTrue(Arrays.stream(Atm10LobbyVelocityPlugin.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .noneMatch(name -> name.contains("YouerAgent")));
        assertTrue(Arrays.stream(Atm10LobbyVelocityPlugin.class.getDeclaredMethods())
                .map(Method::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .noneMatch(name -> name.contains("youer")
                        || name.contains("blockstategrace")
                        || name.contains("blockstatepoll")));
    }

    @Test
    void missingStructuralEvidenceReturnsImmediatePassthroughDecision() throws Exception {
        Atm10LobbyVelocityPlugin plugin = new Atm10LobbyVelocityPlugin(
                null, null, Path.of("."));
        Arrays.stream(Atm10LobbyVelocityPlugin.class.getDeclaredFields())
                .filter(field -> field.getType() == ReviewedBlockStateProfileCatalog.class)
                .forEach(field -> setField(field, plugin, ReviewedBlockStateProfileCatalog.empty()));

        Class<?> sessionType = Class.forName(
                Atm10LobbyVelocityPlugin.class.getName() + "$BridgeSession");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(UUID.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(UUID.randomUUID());
        Field contract = sessionType.getDeclaredField("fullClientContractSha256");
        contract.setAccessible(true);
        contract.set(session, "0".repeat(64));

        Method selector = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "selectBlockStateTranslation", sessionType, int.class);
        selector.setAccessible(true);
        Object decision = selector.invoke(plugin, session, 767);
        Method selection = decision.getClass().getDeclaredMethod("selection");
        Method status = decision.getClass().getDeclaredMethod("status");
        selection.setAccessible(true);
        status.setAccessible(true);

        assertTrue(((Optional<?>) selection.invoke(decision)).isEmpty());
        assertEquals("NO_EXACT_EMBEDDED_EVIDENCE", status.invoke(decision));
        ((AutoCloseable) decision).close();
    }

    @Test
    void exactMapWithoutAResolvedPlayAdapterStillReturnsPassthrough() throws Exception {
        Atm10LobbyVelocityPlugin plugin = new Atm10LobbyVelocityPlugin(
                null, null, Path.of("."));
        ReviewedBlockStateProfileCatalog catalog =
                ReviewedBlockStateProfileCatalog.loadReviewed(
                        FirstHopCardinalAdmissionRegressionTest.class.getClassLoader(),
                        List.of(new ReviewedBlockStateProfileCatalog.Definition(
                                SilentGearEmbeddedProfile.ATM10_NORMAL_8_0_RESOURCE_ROOT,
                                "atm10-normal-8.0_silentgear-4.2.1.1_neoforge-21.1.247",
                                767,
                                ReviewedClientContractEvidence.ATM10_NORMAL_8_0
                                        .fullClientContractSha256(),
                                "de39e16287bfa83396c9f54b1403cf5937d74b4c2ad819922f5540d7e219e419")));
        assertEquals(1, catalog.reviewedProfiles().size());
        Arrays.stream(Atm10LobbyVelocityPlugin.class.getDeclaredFields())
                .filter(field -> field.getType() == ReviewedBlockStateProfileCatalog.class)
                .forEach(field -> setField(field, plugin, catalog));

        Class<?> sessionType = Class.forName(
                Atm10LobbyVelocityPlugin.class.getName() + "$BridgeSession");
        Constructor<?> constructor = sessionType.getDeclaredConstructor(UUID.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(UUID.randomUUID());
        Field contract = sessionType.getDeclaredField("fullClientContractSha256");
        contract.setAccessible(true);
        contract.set(
                session,
                ReviewedClientContractEvidence.ATM10_NORMAL_8_0
                        .fullClientContractSha256());

        Field translator = Atm10LobbyVelocityPlugin.class.getDeclaredField(
                "lobbyPlayPacketTranslator");
        translator.setAccessible(true);
        assertEquals(null, translator.get(plugin));

        Method selector = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "selectBlockStateTranslation", sessionType, int.class);
        selector.setAccessible(true);
        Object decision = selector.invoke(plugin, session, 767);
        Method selection = decision.getClass().getDeclaredMethod("selection");
        Method status = decision.getClass().getDeclaredMethod("status");
        selection.setAccessible(true);
        status.setAccessible(true);

        assertTrue(((Optional<?>) selection.invoke(decision)).isEmpty(),
                "an unavailable PLAY adapter must quarantine only the enrichment");
        assertTrue(status.invoke(decision).toString().contains("UNAVAILABLE"));
        ((AutoCloseable) decision).close();
    }

    @Test
    void playAdapterResolutionFailureQuarantinesOnlyTheEnrichment() {
        LinkageError incompatibleVelocity = new LinkageError("synthetic adapter mismatch");
        AtomicReference<Throwable> diagnostic = new AtomicReference<>();
        VelocityLobbyPlayPacketTranslator resolved =
                Atm10LobbyVelocityPlugin.resolveBlockStateTranslatorOrPassthrough(
                        () -> {
                            throw incompatibleVelocity;
                        },
                        diagnostic::set);

        assertEquals(null, resolved);
        assertEquals(incompatibleVelocity, diagnostic.get());

        // Diagnostic infrastructure is optional too and cannot re-open the failure path.
        assertEquals(
                null,
                Atm10LobbyVelocityPlugin.resolveBlockStateTranslatorOrPassthrough(
                        () -> {
                            throw new IllegalStateException("synthetic resolver failure");
                        },
                        failure -> {
                            throw new IllegalStateException("synthetic logger failure");
                        }));
    }

    @Test
    void blockStateAttachNullThrowOrTimeoutResumesPassthroughWithoutBlocking() throws Exception {
        String source = Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8);
        String attachment = methodBody(
                source, "private void beginBlockStateTranslationAttachment(");
        assertTrue(attachment.contains("translator.attach("));
        assertTrue(attachment.contains("attachment == null"));
        assertTrue(attachment.contains("catch (RuntimeException | LinkageError failure)"));
        assertTrue(attachment.contains("attachment.orTimeout("));
        assertTrue(attachment.contains("ATTACH_THROWN_QUARANTINED"));
        assertTrue(attachment.contains("ATTACH_FAILED_QUARANTINED"));
        assertTrue(attachment.contains("catch (RuntimeException | LinkageError leaseFailure)"));
        assertTrue(attachment.contains("closeBlockStateTranslationLeaseSafely(lease)"));
        assertTrue(attachment.contains("clearBlockStateTranslation(session)"));
        assertTrue(attachment.contains("completeNeoForgeLobbyHandshake("));
        assertFalse(attachment.contains("|| !lease.active()"));
        assertFalse(attachment.contains("reject("));
        assertFalse(attachment.contains(".disconnect("));
        assertFalse(attachment.contains(".join("));
        assertFalse(attachment.contains("attachment.get("));
        assertFalse(attachment.contains("sleep("));
        assertFalse(attachment.contains("delayedExecutor"));
    }

    @Test
    void blockStateLeaseCleanupCannotAbortTheCardinalFallback() {
        AtomicInteger deactivateAttempts = new AtomicInteger();
        AtomicInteger closeAttempts = new AtomicInteger();
        VelocityLobbyPlayPacketTranslator.Lease brokenLease =
                new VelocityLobbyPlayPacketTranslator.Lease() {
                    @Override
                    public boolean active() {
                        throw new LinkageError("synthetic active failure");
                    }

                    @Override
                    public void deactivate() {
                        deactivateAttempts.incrementAndGet();
                        throw new LinkageError("synthetic deactivate failure");
                    }

                    @Override
                    public void close() {
                        closeAttempts.incrementAndGet();
                        throw new IllegalStateException("synthetic close failure");
                    }
                };

        assertDoesNotThrow(() ->
                Atm10LobbyVelocityPlugin.closeBlockStateTranslationLeaseSafely(brokenLease));
        assertEquals(1, deactivateAttempts.get());
        assertEquals(1, closeAttempts.get(),
                "close must still be attempted after a deactivate failure");
    }

    @Test
    void evidenceMissCannotDelayRejectDisconnectOrRerouteTheSession() throws Exception {
        String source = Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8);
        String selector = methodBody(source, "private BlockStateTranslationDecision "
                + "selectBlockStateTranslation(");
        for (String forbidden : new String[] {
                "CompletableFuture", "delayedExecutor", "schedule", "TimeUnit", "sleep(",
                "reject(", ".disconnect(", "setResult(", "requestServer",
                "createConnectionRequest", "State.FAILED"
        }) {
            assertFalse(selector.contains(forbidden),
                    () -> "structural selector mutated admission/liveness: " + forbidden);
        }
        assertTrue(selector.contains("BlockStateTranslationDecision.unavailable("));
        assertTrue(selector.contains("NO_EXACT_EMBEDDED_EVIDENCE"));

        int decisionStart = source.indexOf(
                "BlockStateTranslationDecision translationDecision =");
        assertTrue(decisionStart >= 0);
        int handshake = source.indexOf(
                "completeNeoForgeLobbyHandshake(player, session, currentConfig)",
                decisionStart);
        assertTrue(handshake > decisionStart);
        String missPath = source.substring(
                decisionStart,
                handshake + "completeNeoForgeLobbyHandshake(player, session, currentConfig)"
                        .length());
        assertTrue(missPath.contains("warnUnverifiedBlockStatePassthrough("));
        assertFalse(missPath.contains("reject("));
        assertFalse(missPath.contains(".disconnect("));
        assertFalse(missPath.contains("delayedExecutor"));

        String warning = methodBody(
                source, "private void warnUnverifiedBlockStatePassthrough(");
        assertTrue(warning.contains("UNVERIFIED_PASSTHROUGH"));
        assertTrue(warning.contains("IMMEDIATE_NO_CONTROL_WAIT"));
        assertTrue(warning.contains("admissionDecision=UNCHANGED_CARDINAL"));
        assertTrue(warning.contains("aclMutation=false"));
        assertTrue(warning.contains("pluginInitiatedDisconnect=false"));
        assertFalse(warning.contains("reject("));
        assertFalse(warning.contains(".disconnect("));
    }

    @Test
    void routingObserversCannotUseFingerprintContractOrProfileAsAcl() throws Exception {
        String source = Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8);
        for (String signature : new String[] {
                "public void onInitialServerSelected(",
                "public void onServerPreConnect("
        }) {
            String observer = methodBody(source, signature);
            for (String forbidden : new String[] {
                    "fullClientContract", "Fingerprint", "fingerprint", "BlockState",
                    "RegistryShim", "Atm10Normal81Contract", "setResult(",
                    "requestServer", "createConnectionRequest", ".disconnect(", "reject("
            }) {
                assertFalse(observer.contains(forbidden),
                        () -> signature + " contains ACL/routing mutation token " + forbidden);
            }
        }
    }

    private static void setField(Field field, Object target, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("could not initialize optional structural catalog", exception);
        }
    }

    private static String methodBody(String source, String signature) {
        return methodBody(source, signature, 1);
    }

    private static String methodBody(String source, String signature, int occurrence) {
        assertTrue(occurrence >= 1, "method occurrence must be positive");
        int signatureStart = -1;
        for (int current = 0; current < occurrence; current++) {
            signatureStart = source.indexOf(signature, signatureStart + 1);
        }
        assertTrue(signatureStart >= 0, "method signature must exist: " + signature);
        int bodyStart = source.indexOf('{', signatureStart + signature.length());
        assertTrue(bodyStart >= 0, "method body must exist: " + signature);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = bodyStart; index < source.length(); index++) {
            char current = source.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(bodyStart, index + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }

}
