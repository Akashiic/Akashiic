package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Prevents duplicate or cross-release {@code minecraft:enchantment} transactions. */
final class Atm10Normal81RegistryReplacementIsolationTest {
    private static final Path PLUGIN_SOURCE = Path.of(
            "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java");
    private static final String EXACT_CONTRACT =
            Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256;

    @Test
    void exact81UsesOneFullReplacementAndNeverAppendsPartialEnchantments() {
        RegistryShimPacket full = syntheticPacket(
                Atm10Normal81EnchantmentRegistry.SHIM_ID,
                "minecraft",
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID);
        List<RegistryShimPacket> candidates = List.of(
                ArsNouveauEnchantmentRegistry.packet(65_536),
                AdAstraGiselleEnchantmentRegistry.packet(65_536),
                full,
                ForbiddenArcanusItemModifierRegistry.packet(65_536),
                Atm10Normal81IronsSpellbooksRegistry.packet(65_536));

        RegistryShimPacket replacement = RegistryShimCatalog
                .selectPaperRegistryReplacement(767, candidates, EXACT_CONTRACT)
                .orElseThrow();
        assertEquals(Atm10Normal81EnchantmentRegistry.SHIM_ID, replacement.shimId());
        assertEquals(Atm10Normal81EnchantmentRegistry.REGISTRY_ID, replacement.registryId());

        List<RegistryShimPacket> tail = RegistryShimCatalog.selectForProfile(
                767,
                null,
                candidates,
                Set.of("minecraft", "ars_nouveau", "ad_astra",
                        "forbidden_arcanus", "irons_spellbooks"),
                EXACT_CONTRACT);
        assertTrue(tail.stream().noneMatch(packet ->
                packet.registryId().equals(Atm10Normal81EnchantmentRegistry.REGISTRY_ID)));
        assertEquals(
                tail.size(),
                tail.stream().map(RegistryShimPacket::registryId).distinct().count());
    }

    @Test
    void an80RegistryPacketCannotBecomeThe81Replacement() throws Exception {
        SilentGearEmbeddedProfile profile80 = SilentGearEmbeddedProfile.loadAtm10Normal80(
                Atm10Normal81RegistryReplacementIsolationTest.class.getClassLoader(),
                767,
                1_048_576,
                3_145_728);
        RegistryShimPacket enchantment80 = profile80.dynamicRegistries().packets().stream()
                .filter(packet -> packet.registryId().equals(
                        Atm10Normal81EnchantmentRegistry.REGISTRY_ID))
                .findFirst()
                .orElseThrow();

        assertFalse(enchantment80.shimId().equals(
                Atm10Normal81EnchantmentRegistry.SHIM_ID));
        assertThrows(
                IllegalArgumentException.class,
                () -> RegistryShimCatalog.selectPaperRegistryReplacement(
                        767, List.of(enchantment80), EXACT_CONTRACT));
    }

    @Test
    void duplicateOrAliasedEnchantmentReplacementIsRejected() {
        RegistryShimPacket full = syntheticPacket(
                Atm10Normal81EnchantmentRegistry.SHIM_ID,
                "minecraft",
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID);
        RegistryShimPacket alias = syntheticPacket(
                "unreviewed-enchantment-alias",
                "minecraft",
                Atm10Normal81EnchantmentRegistry.REGISTRY_ID);
        ArrayList<RegistryShimPacket> duplicates = new ArrayList<>();
        duplicates.add(full);
        duplicates.add(alias);

        assertThrows(
                IllegalArgumentException.class,
                () -> RegistryShimCatalog.selectPaperRegistryReplacement(
                        767, duplicates, EXACT_CONTRACT));
        assertTrue(RegistryShimCatalog.selectPaperRegistryReplacement(
                766, List.of(full), EXACT_CONTRACT).isEmpty());
        assertTrue(RegistryShimCatalog.selectPaperRegistryReplacement(
                767, List.of(full), "0".repeat(64)).isEmpty());
    }

    @Test
    void missingOrCorruptFullRegistryEvidenceIsQuarantinedAsImmediateAbsence() {
        Atm10Normal81EnchantmentRegistry.RuntimeResolution missing =
                Atm10Normal81EnchantmentRegistry.runtimeResolutionForTest(
                        new ClassLoader(null) {
                        });
        assertTrue(missing.packet().isEmpty());
        assertTrue(missing.quarantineFailure().isPresent());

        ClassLoader partialCorrupt = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.equals(Atm10Normal81EnchantmentRegistry.PROPERTIES_RESOURCE)) {
                    return new ByteArrayInputStream(new byte[] {1});
                }
                return null;
            }
        };
        Atm10Normal81EnchantmentRegistry.RuntimeResolution corrupt =
                Atm10Normal81EnchantmentRegistry.runtimeResolutionForTest(partialCorrupt);
        assertTrue(corrupt.packet().isEmpty());
        assertTrue(corrupt.quarantineFailure().isPresent());

        // The production catalog consumes its runtime quarantine instead of propagating it into
        // plugin initialization. A packaged exact packet may be present or absent during export.
        List<RegistryShimPacket> resolved = RegistryShimCatalog.resolve(
                RegistryShimCatalog.builtInAtmShimIds(), 1_048_576);
        assertTrue(resolved.stream()
                .filter(packet -> packet.shimId().equals(
                        Atm10Normal81EnchantmentRegistry.SHIM_ID))
                .count() <= 1L);
    }

    @Test
    void unavailableVelocityReplacementAdapterIsQuarantinedWithoutPropagation() {
        LinkageError incompatibleVelocity = new LinkageError("synthetic adapter mismatch");
        AtomicReference<Throwable> diagnostic = new AtomicReference<>();
        VelocityRegistryReplacementGuard resolved =
                Atm10LobbyVelocityPlugin.resolveRegistryReplacementGuardOrPassthrough(
                        () -> {
                            throw incompatibleVelocity;
                        },
                        diagnostic::set);

        assertEquals(null, resolved);
        assertEquals(incompatibleVelocity, diagnostic.get());
        assertEquals(
                null,
                Atm10LobbyVelocityPlugin.resolveRegistryReplacementGuardOrPassthrough(
                        () -> {
                            throw new IllegalStateException("synthetic resolver failure");
                        },
                        failure -> {
                            throw new IllegalStateException("synthetic logger failure");
                        }));
    }

    @Test
    void productiveWiringArmsBeforePrefixAndKeepsFenceThroughPaperConfiguration()
            throws Exception {
        String source = Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8);
        String entry = methodBody(source, "private void completeNeoForgeLobbyHandshake(");
        int begin = entry.indexOf("beginRegistryReplacementAttachment(");
        int continuation = entry.indexOf("continueNeoForgeLobbyHandshake(");
        assertTrue(begin >= 0 && begin < continuation,
                "replacement must be decided before CONFIGURATION prefix continuation");

        String attachment = methodBody(
                source, "private boolean beginRegistryReplacementAttachment(");
        assertTrue(attachment.contains("RegistryShimCatalog.selectPaperRegistryReplacement("));
        int packetReady = attachment.indexOf("RegistryShimPacket replacementPacket");
        assertTrue(packetReady > 0);
        String evidenceMiss = attachment.substring(0, packetReady);
        assertTrue(evidenceMiss.contains("return false;"));
        assertFalse(evidenceMiss.contains("orTimeout("));
        assertFalse(evidenceMiss.contains("delayedExecutor"));
        assertFalse(attachment.contains("reject("));
        assertFalse(attachment.contains(".disconnect("));

        int attach = attachment.indexOf("guard.attach(");
        int completionObserver = attachment.indexOf("lease.completion().whenComplete(");
        int resume = attachment.lastIndexOf("continueNeoForgeLobbyHandshake(");
        assertTrue(attach > packetReady);
        assertTrue(attachment.contains("attachment.orTimeout("));
        assertFalse(attachment.contains("attachment.join("));
        assertFalse(attachment.contains("attachment.get("));
        assertFalse(attachment.contains("sleep("));
        assertFalse(attachment.contains("delayedExecutor"));
        assertTrue(completionObserver > attach && completionObserver < resume,
                "promise completion observer must be registered before handshake resumes");

        String proof = methodBody(
                source, "private void recordRegistryReplacementCompletion(");
        int failureCheck = proof.indexOf("if (failure != null || receipt == null)");
        int exactCheck = proof.indexOf("if (!expected.equals(receipt))");
        int persist = proof.indexOf("completed.add(receipt)");
        assertTrue(failureCheck >= 0 && failureCheck < exactCheck && exactCheck < persist);
        assertFalse(proof.substring(persist).contains(
                "clearRegistryReplacementAttachment(session)"),
                "replacement fence must remain active to consume a second registry packet");
        assertFalse(proof.contains("reject("));
        assertFalse(proof.contains(".disconnect("));
        assertFalse(proof.contains("sendLobbyReady("));

        String cleanup = methodBody(
                source, "private static void clearRegistryReplacementAttachment(");
        assertTrue(cleanup.contains("attachment.cancel(false)"));
        assertTrue(cleanup.contains("lease.close()"));
        assertFalse(cleanup.contains("removeRegistryReplacementProof("),
                "normal detach must preserve an already completed receipt");

        String finish = methodBody(
                source, "public EventTask onPlayerFinishConfiguration(");
        assertFalse(finish.contains("clearRegistryReplacementAttachment(session)"),
                "replacement fence must also protect every reviewed tail write");
        String finished = methodBody(
                source, "public void onPlayerFinishedConfiguration(");
        assertTrue(finished.contains("clearRegistryReplacementAttachment(session)"),
                "replacement fence must detach only after all CONFIG writes finish");

        String warning = methodBody(
                source, "private void warnRegistryReplacementPassthrough(");
        assertTrue(warning.contains("fallback=PAPER_REGISTRY_PASSTHROUGH"));
        assertTrue(warning.contains("recipeLifecycle=WITHHOLD"));
        assertTrue(warning.contains("admissionDecision=UNCHANGED_CARDINAL"));
        assertTrue(warning.contains("pluginInitiatedDisconnect=false"));
        assertTrue(warning.contains("routeMutation=false"));
    }

    private static RegistryShimPacket syntheticPacket(
            String shimId, String namespace, String registryId) {
        byte[] body = {1};
        return new RegistryShimPacket(
                shimId,
                namespace,
                registryId,
                1,
                body,
                sha256(body));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static String methodBody(String source, String signature) {
        int signatureStart = source.indexOf(signature);
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
