package br.com.atmbrasil.lobby.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DiagnosticLogTest {
    @Test
    void disabledGateSuppressesOnlyItsExplicitDiagnosticSink() {
        List<String> records = new ArrayList<>();
        AtomicInteger evaluations = new AtomicInteger();
        DiagnosticLog diagnostics = new DiagnosticLog(records::add);

        diagnostics.info(() -> {
            evaluations.incrementAndGet();
            return "hidden";
        });

        assertFalse(diagnostics.enabled());
        assertEquals(0, evaluations.get());
        assertEquals(List.of(), records);

        records.add("operational");
        assertEquals(List.of("operational"), records,
                "the diagnostic gate must not intercept the operational logger");
    }

    @Test
    void gateCanBeEnabledAndDisabledWithoutReplacingTheOperationalLogger() {
        List<String> records = new ArrayList<>();
        DiagnosticLog diagnostics = new DiagnosticLog(records::add);

        diagnostics.setEnabled(true);
        diagnostics.info(() -> "first diagnostic");
        diagnostics.setEnabled(false);
        diagnostics.info(() -> "hidden diagnostic");

        assertFalse(diagnostics.enabled());
        assertEquals(List.of("first diagnostic"), records);
    }

    @Test
    void missingDebugDefaultsFalseAndOnlyBooleansAreAccepted() {
        assertFalse(DiagnosticLog.parseConfigValue(null));
        assertFalse(DiagnosticLog.parseConfigValue(Boolean.FALSE));
        assertTrue(DiagnosticLog.parseConfigValue(Boolean.TRUE));
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticLog.parseConfigValue("true"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DiagnosticLog.parseConfigValue(1));
    }

    @Test
    void packagedPaperConfigurationsDefaultDebugFalse() throws Exception {
        for (String resource : List.of("/config.yml", "/config-v4.example.yml")) {
            try (var input = getClass().getResourceAsStream(resource)) {
                assertTrue(input != null, resource + " must be packaged");
                String contents = new String(
                        input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(contents.contains("debug: false"), resource);
                assertFalse(contents.contains("debug: true"), resource);
                assertTrue(contents.contains("necrotempus-tab:"), resource);
                assertFalse(contents.contains("necrotempus-tab:\n  debug:"),
                        resource + " must use the one global debug gate for NecroTempus");
            }
        }
    }
}
