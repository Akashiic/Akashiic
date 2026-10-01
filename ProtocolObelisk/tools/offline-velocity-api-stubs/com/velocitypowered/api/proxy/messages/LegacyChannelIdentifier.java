package com.velocitypowered.api.proxy.messages;

import java.util.Objects;

/** Minimal offline compile-time representation of a pre-1.13 plugin channel. */
public final class LegacyChannelIdentifier implements ChannelIdentifier {
    private final String name;

    public LegacyChannelIdentifier(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String getName() {
        return name;
    }

    @Override
    public String getId() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof LegacyChannelIdentifier identifier
                && name.equals(identifier.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
