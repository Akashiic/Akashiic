package io.netty.util.concurrent;
@FunctionalInterface
public interface GenericFutureListener<F extends Future<?>> {
    void operationComplete(F future) throws Exception;
}
