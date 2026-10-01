package io.netty.channel;
public class ChannelInboundHandlerAdapter implements ChannelHandler {
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        context.fireChannelRead(message);
    }
    public void channelInactive(ChannelHandlerContext context) throws Exception {}
    public void handlerRemoved(ChannelHandlerContext context) throws Exception {}
}
