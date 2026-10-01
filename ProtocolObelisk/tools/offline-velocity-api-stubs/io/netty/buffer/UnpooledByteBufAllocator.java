package io.netty.buffer;

public final class UnpooledByteBufAllocator implements ByteBufAllocator {
    public static final UnpooledByteBufAllocator DEFAULT = new UnpooledByteBufAllocator();

    private UnpooledByteBufAllocator() {}

    @Override
    public ByteBuf buffer(int initialCapacity) {
        return buffer(initialCapacity, Integer.MAX_VALUE);
    }

    @Override
    public ByteBuf buffer(int initialCapacity, int maximumCapacity) {
        return new SimpleByteBuf(initialCapacity, maximumCapacity);
    }
}
