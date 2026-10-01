package io.netty.channel;
public interface EventLoop {
    boolean inEventLoop();
    void execute(Runnable command);
}
