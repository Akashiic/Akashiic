package io.netty.util.concurrent;
public interface Future<V> extends java.util.concurrent.Future<V> {
    boolean isSuccess();
    Throwable cause();
    Future<V> addListener(GenericFutureListener<? extends Future<? super V>> listener);
}
