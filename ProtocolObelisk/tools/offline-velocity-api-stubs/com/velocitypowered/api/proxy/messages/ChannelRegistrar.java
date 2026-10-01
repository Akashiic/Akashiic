package com.velocitypowered.api.proxy.messages;

public interface ChannelRegistrar {
    void register(ChannelIdentifier... identifiers);

    void unregister(ChannelIdentifier... identifiers);
}
