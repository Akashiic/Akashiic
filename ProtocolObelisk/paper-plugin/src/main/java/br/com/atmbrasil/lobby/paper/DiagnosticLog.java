package br.com.atmbrasil.lobby.paper;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Explicit gate for verbose diagnostics; it never intercepts operational log records. */
final class DiagnosticLog {
    private final Consumer<String> infoSink;
    private volatile boolean enabled;

    DiagnosticLog(Consumer<String> infoSink) {
        this.infoSink = Objects.requireNonNull(infoSink, "infoSink");
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    void info(Supplier<String> message) {
        Objects.requireNonNull(message, "message");
        if (enabled) {
            infoSink.accept(Objects.requireNonNull(message.get(), "diagnostic message"));
        }
    }

    static boolean parseConfigValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean enabled) {
            return enabled;
        }
        throw new IllegalArgumentException("debug must be true or false");
    }
}
