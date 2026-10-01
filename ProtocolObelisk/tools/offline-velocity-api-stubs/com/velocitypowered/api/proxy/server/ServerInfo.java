package com.velocitypowered.api.proxy.server;

import java.net.InetSocketAddress;
import java.util.Objects;

public final class ServerInfo {
    private final String name;
    private final InetSocketAddress address;

    public ServerInfo(String name, InetSocketAddress address) {
        this.name = Objects.requireNonNull(name, "name");
        this.address = Objects.requireNonNull(address, "address");
    }

    public String getName() {
        return name;
    }

    public InetSocketAddress getAddress() {
        return address;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ServerInfo serverInfo
                && name.equals(serverInfo.name)
                && address.equals(serverInfo.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, address);
    }

    @Override
    public String toString() {
        return "ServerInfo{name=" + name + ", address=" + address + '}';
    }
}
