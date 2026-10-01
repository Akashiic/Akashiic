package com.velocitypowered.api.proxy.messages;
public final class MinecraftChannelIdentifier implements ChannelIdentifier {
    private final String id;
    private MinecraftChannelIdentifier(String id){ this.id=id; }
    public static MinecraftChannelIdentifier from(String id){ return new MinecraftChannelIdentifier(id); }
    @Override public String getId(){ return id; }
}
