package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fences the reviewed structural key from observed query identity and backend relay state. */
final class Atm10Normal81StructuralNormalizationWiringTest {
    private static final Path PLUGIN_SOURCE = Path.of(
            "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java");
    private static final String EXACT = Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256;
    private static final String UNKNOWN = "0".repeat(64);

    @Test
    void mapConsumerUsesNormalizedIdentityWithoutOverwritingTheObservation() throws Exception {
        Object session = session(UNKNOWN, EXACT);
        Atm10LobbyVelocityPlugin plugin = pluginWithReviewed81Map();

        assertEquals(EXACT, structuralKey(session));
        assertEquals("EXACT_EMBEDDED_TRANSLATOR_UNAVAILABLE",
                blockStateStatus(plugin, session, 767));
        assertEquals(UNKNOWN, get(session, "fullClientContractSha256"));
        assertEquals(EXACT, get(session, "normalizedClientContractSha256"));
        assertNull(get(plugin, "lobbyPlayPacketTranslator"));
    }

    @Test
    void rawFallbackAndUnreviewedOrLegacySessionsKeepTheirExistingSelection() throws Exception {
        Atm10LobbyVelocityPlugin plugin = pluginWithReviewed81Map();
        Object exactRaw = session(EXACT, null);
        assertEquals(EXACT, structuralKey(exactRaw));
        assertEquals("EXACT_EMBEDDED_TRANSLATOR_UNAVAILABLE",
                blockStateStatus(plugin, exactRaw, 767));

        for (Object unknown : List.of(session(UNKNOWN, null), session(UNKNOWN, UNKNOWN))) {
            assertEquals(UNKNOWN, structuralKey(unknown));
            assertEquals("NO_EXACT_EMBEDDED_EVIDENCE",
                    blockStateStatus(plugin, unknown, 767));
        }
        assertEquals("NO_EXACT_EMBEDDED_EVIDENCE",
                blockStateStatus(plugin, session(UNKNOWN, EXACT), 5));
        assertNull(structuralKey(session(null, null)));
    }

    @Test
    void normalizedConfigKeySelectsTheWholeCatalogButUnknownAndLegacyDoNot() throws Exception {
        TransientServerConfigPlanner.Plan baseline = TransientServerConfigPlanner.plan(
                List.of("securitycraft-server.toml"), List.of(), false, 0);
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        Object reviewedSession = session(UNKNOWN, EXACT);
        TransientServerConfigPlanner.Plan enriched =
                TransientServerConfigPlanner.withReviewedContractCatalog(
                        baseline, 767, structuralKey(reviewedSession), Optional.of(catalog));

        assertTrue(enriched.reviewedCatalogApplied());
        assertEquals(catalog.fileNames(), enriched.configs());
        assertEquals(288, enriched.configs().size());
        assertEquals(651_792, enriched.reviewedCatalogEncodedBytes());
        assertSame(baseline, TransientServerConfigPlanner.withReviewedContractCatalog(
                baseline, 767, structuralKey(session(UNKNOWN, UNKNOWN)), Optional.of(catalog)));
        assertSame(baseline, TransientServerConfigPlanner.withReviewedContractCatalog(
                baseline, 5, structuralKey(reviewedSession), Optional.of(catalog)));
        assertEquals(UNKNOWN, get(reviewedSession, "fullClientContractSha256"));
    }

    @Test
    void normalizedEnchantmentKeySelectsTheRealFullReplacementOnlyFor767() throws Exception {
        RegistryShimPacket full = Atm10Normal81EnchantmentRegistry.packet(65_536);
        Object reviewedSession = session(UNKNOWN, EXACT);
        assertSame(full, RegistryShimCatalog.selectPaperRegistryReplacement(
                767, List.of(full), structuralKey(reviewedSession)).orElseThrow());
        assertEquals(139, full.entryCount());
        assertEquals("minecraft:enchantment", full.registryId());
        assertTrue(RegistryShimCatalog.selectPaperRegistryReplacement(
                767, List.of(full), structuralKey(session(UNKNOWN, UNKNOWN))).isEmpty());
        assertTrue(RegistryShimCatalog.selectPaperRegistryReplacement(
                5, List.of(full), structuralKey(reviewedSession)).isEmpty());
        assertEquals(UNKNOWN, get(reviewedSession, "fullClientContractSha256"));
    }

    @Test
    void normalizedNeoVitaeKeySelectsItsRegistryAndPairedTagsTogether() throws Exception {
        RegistryShimPacket packet = Atm10Normal81NeoVitaeSentientClosure.loadForTest(
                getClass().getClassLoader(), 65_536).packet();
        Object reviewedSession = session(UNKNOWN, EXACT);
        List<RegistryShimPacket> selected = RegistryShimCatalog.selectForProfile(
                767, null, List.of(packet), Set.of("minecraft"), structuralKey(reviewedSession));
        assertEquals(List.of(packet), selected);
        EmbeddedRegistryTagsProfile tags = RegistryShimCatalog.selectTagsForTransaction(
                767, null, selected, structuralKey(reviewedSession));
        assertEquals("neovitae:sentient_upgrades", tags.registryId());
        assertEquals(6, tags.tags().size());
        assertEquals(101, tags.totalMembers());

        assertTrue(RegistryShimCatalog.selectForProfile(
                767, null, List.of(packet), Set.of("neovitae"), UNKNOWN).isEmpty());
        assertTrue(RegistryShimCatalog.selectTagsForTransaction(
                767, null, List.of(packet), UNKNOWN).isEmpty());
        assertTrue(RegistryShimCatalog.selectForProfile(
                5, null, List.of(packet), Set.of("neovitae"), EXACT).isEmpty());
        assertTrue(RegistryShimCatalog.selectTagsForTransaction(
                5, null, List.of(packet), EXACT).isEmpty());
        assertEquals(UNKNOWN, get(reviewedSession, "fullClientContractSha256"));
    }

    @Test
    void productionWiresTheNormalizedKeyIntoEveryStructuralConsumer() throws Exception {
        String source = source();
        String query = compact(methodBody(source, "private void receiveNeoForgeQuery("));
        assertTrue(query.contains("String normalizedClientContractSha256 = reviewedNormal81Variant"));
        assertTrue(query.contains("::normalizedFullClientContractSha256)"));
        assertTrue(query.contains("TransientServerConfigPlanner.withReviewedContractCatalog( "
                + "configPlan, minecraftProtocol, normalizedClientContractSha256, reviewedConfigCatalog)"));
        assertTrue(query.contains("session.normalizedClientContractSha256 = normalizedClientContractSha256;"));

        String map = compact(methodBody(source,
                "private BlockStateTranslationDecision selectBlockStateTranslation("));
        assertTrue(map.contains(".findStructuralEnrichment( minecraftProtocol, structuralClientContract(session))"));
        assertFalse(map.contains("session.fullClientContractSha256"));

        String replacement = compact(methodBody(source,
                "private boolean beginRegistryReplacementAttachment("));
        // Since the 8.2-lineage transform (HF3) the negotiated protocol is read once into a local.
        assertTrue(replacement.contains(
                "int clientProtocol = player.getProtocolVersion().getProtocol();"));
        assertTrue(replacement.contains("RegistryShimCatalog.selectPaperRegistryReplacement( "
                + "clientProtocol, registryShimPackets, structuralClientContract(session))"));
        assertTrue(replacement.contains("Atm10Normal81Contract.matchesStructuralIdentity( "
                + "clientProtocol, structuralClientContract(session))"));
        assertFalse(replacement.contains("session.fullClientContractSha256"));

        String tail = compact(methodBody(source, "private void injectDynamicRegistryShims("));
        assertTrue(tail.contains("RegistryShimCatalog.selectForProfile( "
                + "player.getProtocolVersion().getProtocol(), selectedProfile, registryShimPackets, "
                + "session.advertisedNamespaces, structuralClientContract(session))"));
        assertTrue(tail.contains("RegistryShimCatalog.selectTagsForTransaction( "
                + "player.getProtocolVersion().getProtocol(), selectedProfile, packets, structuralClientContract(session))"));
        assertFalse(tail.contains("session.fullClientContractSha256"));

        String completion = compact(methodBody(source,
                "private void completeLobbyClientInitialization("));
        int contextStart = completion.indexOf("new PaperRecipeLifecyclePolicy.Context(");
        int contextEnd = completion.indexOf("session.registryShimReceipts)", contextStart);
        assertTrue(contextStart >= 0 && contextEnd > contextStart);
        String recipeContext = completion.substring(contextStart, contextEnd);
        assertTrue(recipeContext.contains("structuralClientContract(session)"));
        assertFalse(recipeContext.contains("session.fullClientContractSha256"));
    }

    @Test
    void rawQueryIdentityStillOwnsCachingTelemetryAndBackendRelay() throws Exception {
        String source = source();
        String query = compact(methodBody(source, "private void receiveNeoForgeQuery("));
        assertTrue(query.contains("ChannelContractSignature.from(decodedClientRegistry)"));
        assertTrue(query.contains("new LobbyNegotiationPlanCache.Key( minecraftProtocol, "
                + "registryFingerprint, contractSignatures.fullContractSha256(),"));
        assertTrue(query.contains("session.fullClientContractSha256 = contractSignatures.fullContractSha256();"));
        assertFalse(query.contains("session.fullClientContractSha256 = normalizedClientContractSha256"));
        assertTrue(query.contains("session.backendNeoForgeCapabilityRelay.captureValidatedAdvertisement( "
                + "payload, registryFingerprint)"));
        assertTrue(query.contains("player.getUsername(), session.fullClientContractSha256, "
                + "normalizedClientContractSha256, reviewedClientContractVariant"));

        String diagnostic = methodBody(source, "private void warnUnverifiedBlockStatePassthrough(");
        assertTrue(diagnostic.contains("shortFingerprint(session.fullClientContractSha256)"));
        assertFalse(diagnostic.contains("structuralClientContract(session)"));
    }

    @Test
    void eachLobbyCycleClearsObservedAndNormalizedEvidenceBeforeProtocolBranching() throws Exception {
        String arm = compact(methodBody(source(), "private static void armLobbyLocked("));
        String rawReset = "session.fullClientContractSha256 = null;";
        String normalizedReset = "session.normalizedClientContractSha256 = null;";
        String legacyBranch = "session.negotiation = ClientNegotiation.PROTOCOL_BYPASS;";
        assertTrue(arm.contains("session.lobbyCycleGeneration++;"));
        assertTrue(arm.contains(rawReset));
        assertTrue(arm.contains(normalizedReset));
        assertTrue(arm.contains("session.reviewedClientContractVariant = \"none\";"));
        assertTrue(arm.contains("session.reviewedTransientConfigCatalog = null;"));
        assertTrue(arm.contains("session.backendNeoForgeCapabilityRelay.beginLobbyCycle();"));
        assertTrue(arm.contains(legacyBranch));
        assertTrue(arm.indexOf(rawReset) < arm.indexOf(legacyBranch));
        assertTrue(arm.indexOf(normalizedReset) < arm.indexOf(legacyBranch));
        assertTrue(arm.contains("session.state = State.LOBBY_READY;"));
    }

    private static Atm10LobbyVelocityPlugin pluginWithReviewed81Map() throws Exception {
        Atm10LobbyVelocityPlugin plugin = new Atm10LobbyVelocityPlugin(null, null, Path.of("."));
        ReviewedBlockStateProfileCatalog catalog = ReviewedBlockStateProfileCatalog.loadReviewed(
                Atm10Normal81StructuralNormalizationWiringTest.class.getClassLoader(),
                List.of(new ReviewedBlockStateProfileCatalog.Definition(
                        "blockstate-profiles/atm10-normal-8.1-neoforge-21.1.249/",
                        Atm10Normal81Contract.ID, 767, EXACT,
                        "394756c142fda290e530318a306bb0c68d108825060b5f02b94c13f2c7418600")));
        assertTrue(catalog.diagnostics().isEmpty());
        assertEquals(1, catalog.reviewedProfiles().size());
        set(plugin, "reviewedBlockStateProfiles", catalog);
        return plugin;
    }

    private static Object session(String raw, String normalized) throws Exception {
        Class<?> type = Class.forName(Atm10LobbyVelocityPlugin.class.getName() + "$BridgeSession");
        Constructor<?> constructor = type.getDeclaredConstructor(UUID.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(UUID.randomUUID());
        set(session, "fullClientContractSha256", raw);
        set(session, "normalizedClientContractSha256", normalized);
        return session;
    }

    private static String structuralKey(Object session) throws Exception {
        Method key = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "structuralClientContract", session.getClass());
        key.setAccessible(true);
        return (String) key.invoke(null, session);
    }

    private static String blockStateStatus(
            Atm10LobbyVelocityPlugin plugin, Object session, int protocol) throws Exception {
        Method selector = Atm10LobbyVelocityPlugin.class.getDeclaredMethod(
                "selectBlockStateTranslation", session.getClass(), int.class);
        selector.setAccessible(true);
        Object decision = selector.invoke(plugin, session, protocol);
        try {
            Method selection = decision.getClass().getDeclaredMethod("selection");
            Method status = decision.getClass().getDeclaredMethod("status");
            selection.setAccessible(true);
            status.setAccessible(true);
            assertTrue(((Optional<?>) selection.invoke(decision)).isEmpty());
            return (String) status.invoke(decision);
        } finally {
            ((AutoCloseable) decision).close();
        }
    }

    private static Object get(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static String source() throws Exception {
        return Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8);
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing production method " + signature);
        int bodyStart = source.indexOf('{', start + signature.length());
        assertTrue(bodyStart >= 0);
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = bodyStart; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(bodyStart, index + 1);
            }
        }
        throw new AssertionError("unterminated production method " + signature);
    }
}
