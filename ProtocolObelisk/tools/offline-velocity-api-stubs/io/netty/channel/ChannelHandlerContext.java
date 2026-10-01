package io.netty.channel;
public interface ChannelHandlerContext {
    String name();
    ChannelHandler handler();
    void write(Object message, ChannelPromise promise);
    ChannelHandlerContext fireChannelRead(Object message);
    io.netty.buffer.ByteBufAllocator alloc();
}
