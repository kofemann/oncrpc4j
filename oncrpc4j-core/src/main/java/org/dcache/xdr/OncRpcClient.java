/*
 * Copyright (c) 2009 - 2015 Deutsches Elektronen-Synchroton,
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
package org.dcache.xdr;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.ConnectionProbe;
import org.glassfish.grizzly.NIOTransportBuilder;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.Transport;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.nio.transport.UDPNIOTransportBuilder;
import org.glassfish.grizzly.strategies.SameThreadIOStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.dcache.xdr.GrizzlyUtils.rpcMessageReceiverFor;
import static com.google.common.base.Throwables.getRootCause;
import static com.google.common.base.Throwables.propagateIfPossible;

public class OncRpcClient implements AutoCloseable {

    private final static Logger _log = LoggerFactory.getLogger(OncRpcClient.class);
    private final InetSocketAddress _socketAddress;
    private final int _localPort;
    private final Transport _transport;
    private final ReplyQueue _replyQueue = new ReplyQueue();

    public OncRpcClient(InetAddress address, int protocol, int port) {
        this(new InetSocketAddress(address, port), protocol, -1, null);
    }

    public OncRpcClient(InetAddress address, int protocol, int port, int localPort) {
        this(new InetSocketAddress(address, port), protocol, localPort, null);
    }

    public OncRpcClient(InetAddress address, int protocol, int port, int localPort, IoStrategy ioStrategy) {
        this(new InetSocketAddress(address, port), protocol, localPort, ioStrategy);
    }

    public OncRpcClient(InetSocketAddress socketAddress, int protocol) {
        this(socketAddress, protocol, -1, null);
    }

    public OncRpcClient(InetSocketAddress socketAddress, int protocol, int localPort, IoStrategy ioStrategy) {

        _socketAddress = socketAddress;
        _localPort = localPort;

        NIOTransportBuilder transportBuilder;

        if (protocol == IpProtocolType.TCP) {
            transportBuilder = TCPNIOTransportBuilder.newInstance();
        } else if (protocol == IpProtocolType.UDP) {
            transportBuilder = UDPNIOTransportBuilder.newInstance();
        } else {
            throw new IllegalArgumentException("Unsupported protocol type: " + protocol);
        }

        if (ioStrategy != null) {
            transportBuilder.setIOStrategy(GrizzlyUtils.translate(ioStrategy));
        }
        _transport = transportBuilder.build();

        FilterChainBuilder filterChain = FilterChainBuilder.stateless();
        filterChain.add(new TransportFilter());
        filterChain.add(rpcMessageReceiverFor(_transport));
        filterChain.add(new RpcProtocolFilter(_replyQueue));

        _transport.setProcessor(filterChain.build());
        _transport.setIOStrategy(SameThreadIOStrategy.getInstance());
        _transport.getConnectionMonitoringConfig().addProbes( new ConnectionProbe.Adapter() {
            @Override
            public void onCloseEvent(Connection connection) {
                _replyQueue.handleDisconnect();
            }
        });
    }

    public XdrTransport connect() throws IOException {
        return connect(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    public XdrTransport connect(long timeout, TimeUnit timeUnit) throws IOException {

        _transport.start();
        SocketConnectorHandler asConnectionHandler = (SocketConnectorHandler) _transport;
        Future<Connection> connectFuture;
        if (_localPort > 0) {
            InetSocketAddress localAddress = new InetSocketAddress(_localPort);
            connectFuture = asConnectionHandler.connect(_socketAddress, localAddress);
        } else {
            connectFuture = asConnectionHandler.connect(_socketAddress);
        }

        Connection<InetSocketAddress> _connection;
        try {
            //noinspection unchecked
            _connection = connectFuture.get(timeout, timeUnit);
        } catch (ExecutionException e) {
            Throwable t = getRootCause(e);
            propagateIfPossible(t, IOException.class);
            throw new IOException(e.toString(), e);
        } catch (TimeoutException | InterruptedException e) {
            throw new IOException(e.toString(), e);
        }

        return new ClientTransport(_connection, _replyQueue);
    }

    @Override
    public void close() throws IOException {
        _transport.shutdown();
    }
}
