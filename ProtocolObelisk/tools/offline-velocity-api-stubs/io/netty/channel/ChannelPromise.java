package io.netty.channel;
public interface ChannelPromise extends ChannelFuture {
    boolean trySuccess();
    boolean isVoid();
}
