package com.velocitypowered.api.network;

public final class ProtocolVersion {
    public static final ProtocolVersion MINECRAFT_1_7_6 = new ProtocolVersion(5);

    private final int protocol;

    private ProtocolVersion(int protocol) {
        this.protocol = protocol;
    }

    public static ProtocolVersion getProtocolVersion(int protocol) {
        return protocol == MINECRAFT_1_7_6.protocol
                ? MINECRAFT_1_7_6
                : new ProtocolVersion(protocol);
    }

    public int getProtocol() {
        return protocol;
    }

    public boolean isUnknown() {
        return protocol < 0;
    }

    public boolean isSupported() {
        return !isUnknown();
    }
}
