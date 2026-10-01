package io.netty.channel;
public interface Channel {
    EventLoop eventLoop();
    boolean isActive();
    ChannelPipeline pipeline();
}
