package io.netty.channel;
public class ChannelDuplexHandler implements ChannelOutboundHandler {
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise)
            throws Exception {
        context.write(message, promise);
    }
    public void channelInactive(ChannelHandlerContext context) throws Exception {}
    public void handlerRemoved(ChannelHandlerContext context) throws Exception {}
}
