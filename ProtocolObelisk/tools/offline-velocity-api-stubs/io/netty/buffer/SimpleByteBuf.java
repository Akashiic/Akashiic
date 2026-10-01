package io.netty.buffer;

import java.util.Arrays;

/** Minimal test-only buffer implementation; never packaged into a ProtocolObelisk artifact. */
final class SimpleByteBuf extends ByteBuf {
    private byte[] data;
    private final int maximumCapacity;
    private int readerIndex;
    private int writerIndex;
    private int references = 1;

    SimpleByteBuf(int initialCapacity, int maximumCapacity) {
        if (initialCapacity < 0 || maximumCapacity < initialCapacity) {
            throw new IllegalArgumentException("invalid capacity");
        }
        data = new byte[initialCapacity];
        this.maximumCapacity = maximumCapacity;
    }

    static SimpleByteBuf wrapped(byte[] bytes) {
        SimpleByteBuf buffer = new SimpleByteBuf(bytes.length, bytes.length);
        buffer.data = bytes.clone();
        buffer.writerIndex = bytes.length;
        return buffer;
    }

    @Override
    public int readableBytes() {
        return writerIndex - readerIndex;
    }

    @Override
    public int readerIndex() {
        return readerIndex;
    }

    @Override
    public int writerIndex() {
        return writerIndex;
    }

    @Override
    public boolean isReadable() {
        return readableBytes() > 0;
    }

    @Override
    public short getUnsignedByte(int index) {
        checkIndex(index, 1);
        return (short) (data[index] & 0xff);
    }

    @Override
    public ByteBuf getBytes(int index, byte[] destination) {
        checkIndex(index, destination.length);
        System.arraycopy(data, index, destination, 0, destination.length);
        return this;
    }

    @Override
    public ByteBuf duplicate() {
        SimpleByteBuf duplicate = new SimpleByteBuf(writerIndex, maximumCapacity);
        duplicate.data = Arrays.copyOf(data, Math.max(data.length, writerIndex));
        duplicate.readerIndex = readerIndex;
        duplicate.writerIndex = writerIndex;
        return duplicate;
    }

    @Override
    public ByteBuf asReadOnly() {
        return duplicate();
    }

    @Override
    public ByteBuf slice(int index, int length) {
        checkIndex(index, length);
        byte[] slice = Arrays.copyOfRange(data, index, index + length);
        return wrapped(slice);
    }

    @Override
    public int readUnsignedByte() {
        requireReadable(1);
        return data[readerIndex++] & 0xff;
    }

    @Override
    public int readUnsignedShort() {
        requireReadable(2);
        return readUnsignedByte() << 8 | readUnsignedByte();
    }

    @Override
    public int readInt() {
        requireReadable(4);
        return readUnsignedByte() << 24
                | readUnsignedByte() << 16
                | readUnsignedByte() << 8
                | readUnsignedByte();
    }

    @Override
    public long readLong() {
        requireReadable(8);
        return (long) readInt() << 32 | readInt() & 0xffff_ffffL;
    }

    @Override
    public ByteBuf skipBytes(int length) {
        requireReadable(length);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf readBytes(byte[] destination) {
        requireReadable(destination.length);
        System.arraycopy(data, readerIndex, destination, 0, destination.length);
        readerIndex += destination.length;
        return this;
    }

    @Override
    public ByteBufAllocator alloc() {
        return UnpooledByteBufAllocator.DEFAULT;
    }

    @Override
    public ByteBuf writeByte(int value) {
        ensureWritable(1);
        data[writerIndex++] = (byte) value;
        return this;
    }

    @Override
    public ByteBuf writeShort(int value) {
        return writeByte(value >>> 8).writeByte(value);
    }

    @Override
    public ByteBuf writeInt(int value) {
        return writeByte(value >>> 24)
                .writeByte(value >>> 16)
                .writeByte(value >>> 8)
                .writeByte(value);
    }

    @Override
    public ByteBuf writeLong(long value) {
        return writeInt((int) (value >>> 32)).writeInt((int) value);
    }

    @Override
    public ByteBuf writeBytes(byte[] source) {
        ensureWritable(source.length);
        System.arraycopy(source, 0, data, writerIndex, source.length);
        writerIndex += source.length;
        return this;
    }

    @Override
    public ByteBuf writeBytes(ByteBuf source, int sourceIndex, int length) {
        byte[] bytes = new byte[length];
        source.getBytes(sourceIndex, bytes);
        return writeBytes(bytes);
    }

    @Override
    public int refCnt() {
        return references;
    }

    @Override
    public ByteBuf retain() {
        return retain(1);
    }

    @Override
    public ByteBuf retain(int increment) {
        if (increment <= 0 || references <= 0) {
            throw new IllegalArgumentException("invalid retain");
        }
        references = Math.addExact(references, increment);
        return this;
    }

    @Override
    public ByteBuf touch() {
        return this;
    }

    @Override
    public ByteBuf touch(Object hint) {
        return this;
    }

    @Override
    public boolean release() {
        return release(1);
    }

    @Override
    public boolean release(int decrement) {
        if (decrement <= 0 || decrement > references) {
            throw new IllegalArgumentException("invalid release");
        }
        references -= decrement;
        return references == 0;
    }

    private void checkIndex(int index, int length) {
        if (index < 0 || length < 0 || index > writerIndex - length) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void requireReadable(int length) {
        if (length < 0 || readableBytes() < length) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void ensureWritable(int length) {
        if (length < 0 || writerIndex > maximumCapacity - length) {
            throw new IndexOutOfBoundsException();
        }
        int required = writerIndex + length;
        if (required <= data.length) {
            return;
        }
        int grown = Math.max(required, Math.max(1, data.length) * 2);
        data = Arrays.copyOf(data, Math.min(grown, maximumCapacity));
    }
}
