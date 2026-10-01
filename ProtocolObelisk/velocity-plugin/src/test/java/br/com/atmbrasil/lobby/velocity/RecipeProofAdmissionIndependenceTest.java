package br.com.atmbrasil.lobby.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ensures recipe proof controls one synthetic packet, never player admission. */
final class RecipeProofAdmissionIndependenceTest {
    private static final Path PLUGIN_SOURCE = Path.of(
            "src/main/java/br/com/atmbrasil/lobby/velocity/Atm10LobbyVelocityPlugin.java");

    @Test
    void missingFullEnchantmentProofReturnsWithholdWithoutFailure() {
        RegistryShimReceipt irons = RegistryShimReceipt.from(
                Atm10Normal81IronsSpellbooksRegistry.packet(65_536));
        Atm10Normal81ServerConfigCatalog.Catalog catalog =
                Atm10Normal81ServerConfigCatalog.catalog();
        PaperRecipeLifecyclePolicy.Context context = new PaperRecipeLifecyclePolicy.Context(
                true,
                true,
                true,
                Atm10Normal81Contract.FULL_CLIENT_CONTRACT_SHA256,
                Set.of("minecraft", "irons_spellbooks"),
                Set.of(),
                catalog.fileNames(),
                catalog.id(),
                catalog.nameSequenceSha256(),
                catalog.payloadSequenceSha256(),
                catalog.totalEncodedBytes(),
                Set.of(irons));

        PaperRecipeLifecyclePolicy.Evaluation evaluation =
                PaperRecipeLifecyclePolicy.decide(
                        context, Optional.empty(), Optional.of(catalog));
        assertFalse(evaluation.release());
        assertEquals(PaperRecipeLifecyclePolicy.Basis.NONE, evaluation.basis());
        assertEquals(
                PaperRecipeLifecyclePolicy.Decision
                        .WITHHOLD_UNREVIEWED_STRUCTURAL_PROFILE,
                evaluation.decision());
    }

    @Test
    void everyWithholdDecisionIsNonReleasingAndPolicyHasNoPlayerSurface() {
        Arrays.stream(PaperRecipeLifecyclePolicy.Decision.values())
                .filter(decision -> decision.name().startsWith("WITHHOLD_"))
                .forEach(decision -> assertFalse(decision.release()));

        for (Method method : PaperRecipeLifecyclePolicy.class.getDeclaredMethods()) {
            assertFalse(method.getReturnType().getName()
                    .startsWith("com.velocitypowered.api"));
            assertTrue(Arrays.stream(method.getParameterTypes())
                    .map(Class::getName)
                    .noneMatch(name -> name.startsWith("com.velocitypowered.api")
                            || name.endsWith(".Player")));
        }
    }

    @Test
    void withheldBranchMarksInitializationCompleteWithoutDisconnecting() throws Exception {
        String source = Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8);
        String completion = methodBody(
                source, "private void completeLobbyClientInitialization(");
        int initialized = completion.indexOf(
                "session.lobbyClientInitializationComplete = true");
        int releaseDecision = completion.indexOf(
                "if (finalRecipeLifecycleEvaluation.release())");
        int withheldBranch = completion.indexOf("} else {", releaseDecision);
        assertTrue(initialized >= 0 && initialized < releaseDecision);
        assertTrue(withheldBranch > releaseDecision);

        String withheld = completion.substring(withheldBranch);
        assertTrue(withheld.contains("recipeLifecycle=WITHHELD"));
        assertTrue(withheld.contains("pluginInitiatedDisconnect=false"));
        assertFalse(withheld.contains("sendLobbyReady("));
        assertFalse(withheld.contains("reject("));
        assertFalse(withheld.contains(".disconnect("));
        assertFalse(withheld.contains("requestServer"));
        assertFalse(withheld.contains("createConnectionRequest"));
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
