package org.dcache.oncrpc4j.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;


public class RpcMessageTcpEncoder extends ChannelOutboundHandlerAdapter {

    /**
     * RPC fragment record marker mask
     */
    private final static int RPC_LAST_FRAG = 0x80000000;
    /**
     * RPC fragment size mask
     */
    private final static int RPC_SIZE_MASK = 0x7fffffff;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {

            ByteBuf b = (ByteBuf)msg;
            int len = b.readableBytes() | RPC_LAST_FRAG;

            ByteBuf marker = Unpooled.buffer(4);
            marker.writeInt(len);
            ByteBuf composite = Unpooled.wrappedBuffer(marker, b);
            ctx.write(b, promise);
    }
}
