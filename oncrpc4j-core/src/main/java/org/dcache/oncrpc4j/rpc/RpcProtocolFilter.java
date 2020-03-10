/*
 * Copyright (c) 2009 - 2020 Deutsches Elektronen-Synchroton,
 * Member of the Helmholtz Association, (DESY), HAMBURG, GERMANY
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.oncrpc4j.rpc;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.dcache.oncrpc4j.xdr.Xdr;
import java.nio.channels.CompletionHandler;
import java.util.List;

import org.dcache.oncrpc4j.grizzly.GrizzlyRpcTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RpcProtocolFilter extends MessageToMessageEncoder<Xdr> {

    private final static Logger _log = LoggerFactory.getLogger(RpcProtocolFilter.class);
    private final ReplyQueue _replyQueue;

    public RpcProtocolFilter(ReplyQueue replyQueue) {
        _replyQueue = replyQueue;
    }

    @Override
    public void encode(ChannelHandlerContext ctx, Xdr xdr, List<Object> out) throws Exception {

        xdr.beginDecoding();

        RpcMessage message = new RpcMessage(xdr);
        /**
         * In case of UDP grizzly does not populates connection with correct destination address.
         * We have to get peer address from the request context, which will contain SocketAddress where from
         * request was coming.
         */
        RpcTransport transport = new GrizzlyRpcTransport(ctx.channel(), ctx.channel().remoteAddress(), _replyQueue);

        switch (message.type()) {
            case RpcMessageType.CALL:
                RpcCall call = new RpcCall(message.xid(), xdr, transport);
                try {
                    call.accept();
                    out.add(call);

                } catch (RpcException e) {
                    call.reject(e.getStatus(), e.getRpcReply());
                    _log.info("RPC request rejected: {}", e.getMessage());
                } catch (OncRpcException e) {
                    _log.info("failed to process RPC request: {}", e.getMessage());
                }
            case RpcMessageType.REPLY:
                try {
                    RpcReply reply = new RpcReply(message.xid(), xdr, transport);
                    CompletionHandler<RpcReply, RpcTransport> callback = _replyQueue.get(message.xid());
                    if (callback != null) {
                        if (!reply.isAccepted()) {
                            callback.failed(new OncRpcRejectedException(reply.getRejectStatus()), transport);
                        } else if (reply.getAcceptStatus() != RpcAccepsStatus.SUCCESS) {
                            callback.failed(new OncRpcAcceptedException(reply.getAcceptStatus()), transport);
                        } else {
                            callback.completed(reply, transport);
                        }
                    }
                } catch (OncRpcException e) {
                    _log.warn("failed to decode reply:", e);
                }
            default:
                // bad XDR
        }
    }
}
