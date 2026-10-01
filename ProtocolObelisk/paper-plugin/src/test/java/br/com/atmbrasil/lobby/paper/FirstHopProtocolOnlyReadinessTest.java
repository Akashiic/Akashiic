package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Preserves Paper as a protocol-only lobby backend, never a structural admission authority. */
final class FirstHopProtocolOnlyReadinessTest {
    private static final Path PLUGIN_SOURCE = Path.of(
            "src/main/java/br/com/atmbrasil/lobby/paper/Atm10LobbyPaperPlugin.java");

    @Test
    void validReadinessCannotConsultClientFingerprintContractOrTranslationEvidence()
            throws Exception {
        String source = Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8);
        String readiness = methodBody(source, "private void acceptLobbyReady(")
                .toLowerCase(Locale.ROOT);

        assertTrue(readiness.contains("ready.sessionid()"));
        assertTrue(readiness.contains("accepted protocol-only lobby readiness"));
        for (String forbidden : new String[] {
                "fingerprint", "fullclientcontract", "blockstate", "registryshim",
                "modlist", "neoforge", "youer", "requestserver", "serverpreconnect"
        }) {
            assertFalse(readiness.contains(forbidden),
                    () -> "Paper readiness acquired structural/routing authority: " + forbidden);
        }
    }

    @Test
    void paperMainHasNoYouerOrClientStructuralEvidenceSurface() throws Exception {
        String source = Files.readString(PLUGIN_SOURCE, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        for (String forbidden : new String[] {
                "youeragent", "fullclientcontractsha256", "registryfingerprint",
                "blockstatetranslation", "atm10normal81contract"
        }) {
            assertFalse(source.contains(forbidden),
                    () -> "vanilla Paper backend depends on first-hop evidence: " + forbidden);
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
