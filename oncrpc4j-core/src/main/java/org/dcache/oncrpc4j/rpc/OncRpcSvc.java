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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.dcache.oncrpc4j.grizzly.StartTlsFilter;
import org.dcache.oncrpc4j.rpc.net.IpProtocolType;
import org.dcache.oncrpc4j.rpc.net.InetSocketAddresses;
import org.dcache.oncrpc4j.rpc.gss.GssProtocolFilter;
import org.dcache.oncrpc4j.rpc.gss.GssSessionManager;
import org.dcache.oncrpc4j.portmap.GenericPortmapClient;
import org.dcache.oncrpc4j.portmap.OncPortmapClient;
import org.dcache.oncrpc4j.portmap.OncRpcPortmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;

import static com.google.common.base.Throwables.getRootCause;
import static com.google.common.base.Throwables.propagateIfPossible;
import static org.dcache.oncrpc4j.grizzly.GrizzlyUtils.*;

import java.net.SocketAddress;
import java.util.stream.Collectors;
import javax.net.ssl.SSLParameters;
import org.dcache.oncrpc4j.grizzly.GrizzlyRpcTransport;

public class OncRpcSvc {

    private final static Logger _log = LoggerFactory.getLogger(OncRpcSvc.class);

    private final int _backlog;
    private final boolean _publish;
    private final PortRange _portRange;
    private final String _bindAddress;
    private final boolean _isClient;
    private final List<Bootstrap> _transports = new ArrayList<>();
    private final Set<Channel> _boundConnections =  new HashSet<>();

    private final ExecutorService _requestExecutor;

    private final ReplyQueue _replyQueue = new ReplyQueue();

    private final boolean _withSubjectPropagation;
    /**
     * Handle RPCSEC_GSS
     */
    private final GssSessionManager _gssSessionManager;

    /**
     * SSL context to use, if configured.
     */
    private final SSLContext _sslContext;

    /**
     * SSL parameters that should be applied to SSL engine.
     */
    private final SSLParameters _sslParams;

    /**
     * Start TLS only when requested.
     */
    private final boolean _startTLS;

    /**
     * mapping of registered programs.
     */
    private final Map<OncRpcProgram, RpcDispatchable> _programs =
            new ConcurrentHashMap<>();

    /**
     * Name of this service
     */
    private final String _svcName;

    private final ServerBootstrap bootstrap = new ServerBootstrap();

    /**
     * Create new RPC service with defined configuration.
     * @param builder to build this service
     */
    OncRpcSvc(OncRpcSvcBuilder builder) {
        _publish = builder.isAutoPublish();
        final int protocol = builder.getProtocol();

        if ((protocol & (IpProtocolType.TCP | IpProtocolType.UDP)) == 0) {
            throw new IllegalArgumentException("TCP or UDP protocol have to be defined");
        }

        String serviceName = builder.getServiceName();

        ThreadFactory selectorThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat(serviceName + " SelectorRunner-%d")
                .build();

        if ((protocol & IpProtocolType.TCP) != 0) {
            EventLoopGroup workerGroup = new NioEventLoopGroup(getSelectorPoolSize(builder.getIoStrategy()), selectorThreadFactory);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup);
            bootstrap.channel(NioServerSocketChannel.class);
            _transports.add(bootstrap);
        }

        if ((protocol & IpProtocolType.UDP) != 0) {
            EventLoopGroup workerGroup = new NioEventLoopGroup(getSelectorPoolSize(builder.getIoStrategy()), selectorThreadFactory);
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup);
            bootstrap.channel(NioDatagramChannel.class);
            _transports.add(bootstrap);
        }

        _isClient = builder.isClient();
        _portRange = builder.getMinPort() > 0 ?
                new PortRange(builder.getMinPort(), builder.getMaxPort()) : null;

        _backlog = builder.getBacklog();
        _bindAddress = builder.getBindAddress();

        _requestExecutor = builder.getWorkerThreadExecutorService();
        _gssSessionManager = builder.getGssSessionManager();
        _programs.putAll(builder.getRpcServices());
        _withSubjectPropagation = builder.getSubjectPropagation();
        _svcName = builder.getServiceName();
        _sslContext = builder.getSSLContext();
        _startTLS = builder.isStartTLS();
        _sslParams = builder.getSSLParameters();
    }

    /**
     * Register a new PRC service. Existing registration will be overwritten.
     *
     * @param prog program number
     * @param handler RPC requests handler.
     */
    public void register(OncRpcProgram prog, RpcDispatchable handler) {
        _log.info("Registering new program {} : {}", prog, handler);
        _programs.put(prog, handler);
    }

    /**
     * Unregister given RPC program.
     *
     * @param prog RPC program to unregister.
     */
    public void unregister(OncRpcProgram prog) {
        _log.info("Unregistering program {}", prog);
        _programs.remove(prog);
    }

    /**
     * Add programs to existing services.
     * @param services RPC programs to be served by this service.
     * @deprecated use {@link OncRpcSvcBuilder#withRpcService} instead.
     */
    @Deprecated
    public void setPrograms(Map<OncRpcProgram, RpcDispatchable> services) {
        _programs.putAll(services);
    }

    /**
     * Register services in portmap.
     *
     * @throws IOException
     * @throws UnknownHostException
     */
    private void publishToPortmap(Channel connection, Set<OncRpcProgram> programs) throws IOException {

        OncRpcClient rpcClient = new OncRpcClient(InetAddress.getByName(null),
                IpProtocolType.UDP, OncRpcPortmap.PORTMAP_PORT);
        RpcTransport transport = rpcClient.connect();

        try {
            OncPortmapClient portmapClient = new GenericPortmapClient(transport);

            Set<String> netids = new HashSet<>();
            String username = System.getProperty("user.name");
            String uaddr = InetSocketAddresses.uaddrOf((InetSocketAddress) connection.localAddress());

            String netidBase;
            if (connection instanceof ServerSocketChannel) {
                netidBase = "tcp";
            } else if (connection instanceof DatagramChannel) {
                netidBase = "udp";
            } else {
                // must never happens
                throw new RuntimeException("Unsupported transport type: " + t.getClass().getCanonicalName());
            }

            InetAddress localAddress = ((InetSocketAddress)connection.localAddress()).getAddress();
            if (localAddress instanceof Inet6Address) {
                netids.add(netidBase + "6");
                if (((Inet6Address)localAddress).isIPv4CompatibleAddress()) {
                    netids.add(netidBase);
                }
            } else {
                netids.add(netidBase);
            }

            for (OncRpcProgram program : programs) {
                for (String netid : netids) {
                    try {
                        portmapClient.setPort(program.getNumber(), program.getVersion(),
                                netid, uaddr, username);
                    } catch (OncRpcException | TimeoutException e) {
                        _log.warn("Failed to register program: {}", e.getMessage());
                    }
                }
            }
        } catch (RpcProgUnavailable e) {
            _log.warn("Failed to register at portmap: {}", e.getMessage());
        } finally {
            rpcClient.close();
        }
    }

    /**
     * UnRegister services in portmap.
     *
     * @throws IOException
     * @throws UnknownHostException
     */
    private void clearPortmap(Set<OncRpcProgram> programs) throws IOException {

        OncRpcClient rpcClient = new OncRpcClient(InetAddress.getByName(null),
                IpProtocolType.UDP, OncRpcPortmap.PORTMAP_PORT);
        RpcTransport transport = rpcClient.connect();

        try {
            OncPortmapClient portmapClient = new GenericPortmapClient(transport);

            String username = System.getProperty("user.name");

            for (OncRpcProgram program : programs) {
                try {
                    portmapClient.unsetPort(program.getNumber(),
                            program.getVersion(), username);
                } catch (OncRpcException | TimeoutException e) {
                    _log.info("Failed to unregister program: {}", e.getMessage());
                }
            }
        } catch (RpcProgUnavailable e) {
            _log.info("portmap service not available");
        } finally {
            rpcClient.close();
        }
    }

    public void start() throws IOException {

        if(!_isClient && _publish) {
            clearPortmap(_programs.keySet());
        }

        bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {

            protected void initChannel(SocketChannel channel) throws Exception {
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast("rpc-message-fragment-collector", new RpcMessageParserTCP());
                pipeline.addLast("rpc-request-dispatcher", new RpcDispatcher(_requestExecutor, _programs, _withSubjectPropagation));
                pipeline.addLast("rpc-reply-sender", new RpcMessageTcpEncoder());
            }

        });


        for (Bootstrap t : _transports) {

            ChannelInitializer channelInitializer = new ChannelInitializer<SocketChannel>() {

                protected void initChannel(SocketChannel channel) throws Exception {
                    ChannelPipeline pipeline = channel.pipeline();
                    pipeline.addLast("rpc-message-fragment-collector", new RpcMessageParserTCP());
                    pipeline.addLast("rpc-request-dispatcher", new RpcDispatcher(_requestExecutor, _programs, _withSubjectPropagation));
                    pipeline.addLast("rpc-reply-sender", new RpcMessageTcpEncoder());
                }


            };

            t.handler(channelInitializer);

            if (_sslContext != null) {
                SSLEngineConfigurator serverSSLEngineConfigurator =
                        new SSLEngineConfigurator(_sslContext, false, false, false);

                SSLEngineConfigurator clientSSLEngineConfigurator =
                        new SSLEngineConfigurator(_sslContext, true, false, false);

                if (_sslParams != null) {
                    String[] cipherSuites = _sslParams.getCipherSuites();
                    serverSSLEngineConfigurator.setEnabledCipherSuites(cipherSuites);
                    clientSSLEngineConfigurator.setEnabledCipherSuites(cipherSuites);

                    String[] protocols = _sslParams.getProtocols();
                    serverSSLEngineConfigurator.setEnabledProtocols(protocols);
                    clientSSLEngineConfigurator.setEnabledProtocols(protocols);

                    serverSSLEngineConfigurator.setNeedClientAuth(_sslParams.getNeedClientAuth());
                    serverSSLEngineConfigurator.setWantClientAuth(_sslParams.getWantClientAuth());
                }
                SSLFilter sslFilter = new SSLFilter(serverSSLEngineConfigurator,
                        clientSSLEngineConfigurator);
                filterChain.add(_startTLS ? new StartTlsFilter(sslFilter, _isClient) : sslFilter);
            }

            filterChain.add(rpcMessageReceiverFor(t));
            filterChain.add(new RpcProtocolFilter(_replyQueue));
            // use GSS if configures
            if (_gssSessionManager != null) {
                filterChain.add(new GssProtocolFilter(_gssSessionManager));
            }
            t..addProbes(new ConnectionProbe.Adapter() {
                @Override
                public void onCloseEvent(Connection connection) {
                    if (connection.getCloseReason().getType() == CloseType.REMOTELY) {
                        _replyQueue.handleDisconnect((SocketAddress)connection.getLocalAddress());
                    }
                }
            });

            if(!_isClient) {
                ChannelFuture cf = _portRange == null ?
                        t.bind(_bindAddress, 0) :
                        t.bind(_bindAddress, _portRange);

                _boundConnections.add(cf.channel());

                if (_publish) {
                    publishToPortmap(cf.channel(), _programs.keySet());
                }
            }
            t.bind();

        }
    }

    public void stop() throws IOException {

        if (!_isClient && _publish) {
            clearPortmap(_programs.keySet());
        }

        for (Bootstrap t : _transports) {
            t.config().group().shutdownGracefully();
        }

        _replyQueue.shutdown();
        _requestExecutor.shutdown();
    }

    public void stop(long gracePeriod, TimeUnit timeUnit) throws IOException {

        if (!_isClient && _publish) {
            clearPortmap(_programs.keySet());
        }

        List<Future<?>> transportsShuttingDown = new ArrayList<>();
        for (Bootstrap t : _transports) {
            transportsShuttingDown.add(t.config().group().shutdownGracefully(0, gracePeriod, timeUnit));
        }

        for (Future<?> transportShuttingDown : transportsShuttingDown) {
            try {
                transportShuttingDown.get();
            } catch (InterruptedException e) {
                _log.info("Waiting for graceful shut down interrupted");
            } catch (ExecutionException e) {
                Throwable t = getRootCause(e);
                _log.warn("Exception while waiting for transport to shut down gracefully",t);
            }
        }

        _requestExecutor.shutdown();
    }

    public RpcTransport connect(InetSocketAddress socketAddress) throws IOException {
        return connect(socketAddress, Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    }

    public RpcTransport connect(InetSocketAddress socketAddress, long timeout, TimeUnit timeUnit) throws IOException {

        // in client mode only one transport is defined
        Bootstrap transport = _transports.get(0);

        ChannelFuture connectFuture;
        if (_portRange != null) {
            InetSocketAddress localAddress = new InetSocketAddress(_portRange.getLower());
            connectFuture = transport.connect(socketAddress, localAddress);
        } else {
            connectFuture = transport.connect(socketAddress);
        }

        try {
            //noinspection unchecked
            connectFuture.get(timeout, timeUnit);
            Channel channel = connectFuture.channel();
            return new GrizzlyRpcTransport(channel, _replyQueue);
        } catch (ExecutionException e) {
            Throwable t = getRootCause(e);
            propagateIfPossible(t, IOException.class);
            throw new IOException(e.toString(), e);
        } catch (TimeoutException | InterruptedException e) {
            throw new IOException(e.toString(), e);
        }
    }

    /**
     * Returns the socket address of the endpoint to which this service is bound,
     * or <code>null</code> if it is not bound yet.
     * @param protocol protocol identifier as specified in {@link IpProtocolType}.
     * @return a {@link InetSocketAddress} representing the local endpoint of
     * this service, or <code>null</code> if it is not bound yet.
     */
    public InetSocketAddress getInetSocketAddress(int protocol) {
        Class< ? extends Channel> transportClass = transportFor(protocol);
	return _boundConnections.stream()
		.filter(c -> c.getClass() == transportClass)
		.map(Channel::localAddress)
        .map(InetSocketAddress.class::cast)
		.findAny()
		.orElse(null);
    }

    /**
     * Get name of this service.
     * @return name of this service.
     */
    public String getName() {
	return _svcName;
    }

    @Override
    public String toString() {
	return _boundConnections.stream()
		.map(Channel::localAddress)
		.map(Object::toString)
		.collect(Collectors.joining(",", getName() +"-[", "]"));
    }
}
