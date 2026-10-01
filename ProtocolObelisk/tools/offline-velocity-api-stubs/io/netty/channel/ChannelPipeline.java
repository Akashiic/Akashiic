package io.netty.channel;
import java.util.List;
public interface ChannelPipeline {
    ChannelHandlerContext context(String name);
    ChannelHandlerContext context(ChannelHandler handler);
    List<String> names();
    ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler);
    ChannelPipeline remove(ChannelHandler handler);
}
