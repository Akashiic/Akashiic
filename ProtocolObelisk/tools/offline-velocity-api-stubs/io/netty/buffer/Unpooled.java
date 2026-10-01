package io.netty.buffer;
public final class Unpooled {
    public static final ByteBuf EMPTY_BUFFER = new SimpleByteBuf(0, 0);
    private Unpooled() {}
    public static ByteBuf wrappedBuffer(byte[] bytes) {
        return SimpleByteBuf.wrapped(bytes);
    }
    public static ByteBuf buffer() {
        return UnpooledByteBufAllocator.DEFAULT.buffer(256);
    }
}
