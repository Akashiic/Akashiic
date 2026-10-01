package br.com.atmbrasil.lobby.velocity.necro;

/**
 * Diagnostic-only sink. Passing {@link #disabled()} guarantees that routine transport traces do
 * not reach the operational logger; warnings and failures still use the service logger.
 */
@FunctionalInterface
public interface NecroTempusDiagnostics {
    void log(String message, Object... arguments);

    static NecroTempusDiagnostics disabled() {
        return DisabledHolder.INSTANCE;
    }

    final class DisabledHolder {
        private static final NecroTempusDiagnostics INSTANCE = (message, arguments) -> { };

        private DisabledHolder() {
        }
    }
}
