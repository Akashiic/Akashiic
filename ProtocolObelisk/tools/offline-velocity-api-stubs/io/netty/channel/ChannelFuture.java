package io.netty.channel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
public interface ChannelFuture extends Future<Void> {
    @Override
    ChannelFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener);
}
