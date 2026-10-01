package io.netty.buffer;
import io.netty.util.ReferenceCounted;
public abstract class ByteBuf implements ReferenceCounted {
    public abstract int readableBytes();
    public abstract int readerIndex();
    public abstract int writerIndex();
    public abstract boolean isReadable();
    public abstract short getUnsignedByte(int index);
    public abstract ByteBuf getBytes(int index, byte[] destination);
    public abstract ByteBuf duplicate();
    public abstract ByteBuf asReadOnly();
    public abstract ByteBuf slice(int index, int length);
    public abstract int readUnsignedByte();
    public abstract int readUnsignedShort();
    public abstract int readInt();
    public abstract long readLong();
    public abstract ByteBuf skipBytes(int length);
    public abstract ByteBuf readBytes(byte[] destination);
    public abstract ByteBufAllocator alloc();
    public abstract ByteBuf writeByte(int value);
    public abstract ByteBuf writeShort(int value);
    public abstract ByteBuf writeInt(int value);
    public abstract ByteBuf writeLong(long value);
    public abstract ByteBuf writeBytes(byte[] source);
    public abstract ByteBuf writeBytes(ByteBuf source, int sourceIndex, int length);
}
