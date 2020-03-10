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
package org.dcache.oncrpc4j.grizzly;


import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import org.dcache.oncrpc4j.rpc.ReplyQueue;
import org.dcache.oncrpc4j.xdr.Xdr;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.CompletionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dcache.oncrpc4j.rpc.RpcAuthError;
import org.dcache.oncrpc4j.rpc.RpcAuthException;
import org.dcache.oncrpc4j.rpc.RpcAuthStat;
import org.dcache.oncrpc4j.rpc.RpcTransport;

import static java.util.Objects.requireNonNull;

public class GrizzlyRpcTransport implements RpcTransport {

    private final Channel _connection;
    private final ReplyQueue _replyQueue;
    private final SocketAddress _localAddress;
    private final SocketAddress _remoteAddress;

    private final static Logger _log = LoggerFactory.getLogger(GrizzlyRpcTransport.class);

    public GrizzlyRpcTransport(Channel connection, ReplyQueue replyQueue) {
        this(connection, connection.remoteAddress(), replyQueue);
    }

    public GrizzlyRpcTransport(Channel connection, SocketAddress remoteAddress, ReplyQueue replyQueue) {
        _connection = connection;
        _replyQueue = replyQueue;
        _localAddress = _connection.localAddress();
        _remoteAddress = remoteAddress;
    }

    @Override
    public boolean isOpen() {
        return _connection.isOpen();
    }

    @Override
    public <A> void send(final Xdr xdr, A attachment, CompletionHandler<Integer, ? super A> handler) {
        final ByteBuf buffer = xdr.asBuffer();

        requireNonNull(handler, "CompletionHandler can't be null");

        // pass destination address to handle UDP connections as well
        _connection.write(buffer);
    }

    @Override
    public InetSocketAddress getLocalSocketAddress() {
        return (InetSocketAddress)_localAddress;
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        return (InetSocketAddress)_remoteAddress;
    }

    @Override
    public ReplyQueue getReplyQueue() {
        return _replyQueue;
    }

    @Override
    public RpcTransport getPeerTransport() {
        return new GrizzlyRpcTransport(_connection, getReplyQueue());
    }

    @Override
    public String toString() {
        return getRemoteSocketAddress() + " <=> " + getLocalSocketAddress();
    }

    @Override
    public void startTLS() throws RpcAuthException {
/*        final FilterChain currentChain = (FilterChain) _connection.getProcessor();
        if (currentChain.indexOfType(SSLFilter.class) >= 0) {
            // already enabled
            throw new IllegalStateException("TLS is already enabled.");
        }

        currentChain.stream()
                .filter(StartTlsFilter.class::isInstance)
                .findAny()
                .map(StartTlsFilter.class::cast)
                .orElseThrow(() -> new RpcAuthException("SSL is not configured",
                        new RpcAuthError(RpcAuthStat.AUTH_FAILED)))
                .startTLS(_connection); */
    }
}
