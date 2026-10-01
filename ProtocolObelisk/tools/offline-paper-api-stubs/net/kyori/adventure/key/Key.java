package net.kyori.adventure.key;

import java.util.Objects;

/** Minimal immutable Adventure key used by Paper's channel-listener API. */
public interface Key {
    static Key key(String namespace, String value) {
        return new StubKey(namespace, value);
    }

    String namespace();

    String value();
}

final class StubKey implements Key {
    private final String namespace;
    private final String value;

    StubKey(String namespace, String value) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof Key key
                && namespace.equals(key.namespace())
                && value.equals(key.value());
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, value);
    }

    @Override
    public String toString() {
        return namespace + ':' + value;
    }
}
