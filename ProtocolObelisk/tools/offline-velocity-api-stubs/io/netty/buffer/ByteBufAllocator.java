package io.netty.buffer;
public interface ByteBufAllocator {
    ByteBuf buffer(int initialCapacity);
    ByteBuf buffer(int initialCapacity, int maximumCapacity);
}
