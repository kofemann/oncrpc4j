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

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RpcCall {

    private final static Logger _log = LoggerFactory.getLogger(RpcCall.class);

    /**
     * XID number generator
     */
    private final static AtomicInteger NEXT_XID = new AtomicInteger(0);

    private int _xid;

    /**
     * Supported RPC protocol version
     */
    private final static int RPCVERS = 2;

    /**
     * RPC program number
     */
    private int _prog;

    /**
     * RPC program version number
     */
    private int _version;

    /**
     * RPC program procedure number
     */
    private int _proc;

    /**
     *  RPC protocol version number
     */
    private int _rpcvers;

    /**
     * Authentication credential.
     */
    private RpcAuth _cred;

    /**
     * RPC call transport.
     */
    private final XdrTransport _transport;

    /**
     * Call body.
     */
    private final Xdr _xdr;

    public RpcCall(int prog, int ver, RpcAuth cred, XdrTransport transport) {
        this(prog, ver, cred, new Xdr(Xdr.MAX_XDR_SIZE), transport);
    }

    public RpcCall(int prog, int ver, RpcAuth cred, Xdr xdr, XdrTransport transport) {
        _prog = prog;
        _version = ver;
        _cred = cred;
        _transport = transport;
        _xdr = xdr;
        _proc = 0;
    }

    public RpcCall(int xid, Xdr xdr, XdrTransport transport) {
        _xid = xid;
        _xdr = xdr;
        _transport = transport;
    }

    public RpcCall(int xid, int prog, int ver, int proc, RpcAuth cred, Xdr xdr, XdrTransport transport) {
        _xid = xid;
        _prog = prog;
        _version = ver;
        _proc = proc;
        _cred = cred;
        _xdr = xdr;
        _transport = transport;
        _rpcvers = RPCVERS;
    }

    /**
     * Accept message. Have to be called prior processing RPC call.
     * @throws IOException
     * @throws OncRpcException
     */
    public void accept() throws IOException, OncRpcException {
         _rpcvers = _xdr.xdrDecodeInt();
         if (_rpcvers != RPCVERS) {
            throw new RpcMismatchReply(_rpcvers, 2);
         }

        _prog = _xdr.xdrDecodeInt();
        _version = _xdr.xdrDecodeInt();
        _proc = _xdr.xdrDecodeInt();
        _cred = RpcCredential.decode(_xdr);
     }

    /**
     * Get RPC call program number.
     *
     * @return version number
     */
    public int getProgram() {
        return _prog;
    }

    /**
     * @return the RPC call program version
     */
    public int getProgramVersion() {
        return _version;
    }

    /**
     * @return the RPC call program procedure
     */
    public int getProcedure() {
        return _proc;
    }

    public RpcAuth getCredential() {
        return _cred;
    }

    /**
     * Get RPC {@XdrTransport} used by this call.
     * @return transport
     */
    public XdrTransport getTransport() {
        return _transport;
    }

    /**
     * Get xid associated with this rpc message.
     */
    public int getXid() {
        return _xid;
    }

    /**
     * Get {@link Xdr} stream used by this message.
     * @return xdr stream
     */
    public Xdr getXdr() {
        return _xdr;
    }

    @Override
    public String toString() {
        return String.format("RPCv%d call: program=%d, version=%d, procedure=%d",
                _rpcvers, _prog, _version, _proc);
    }

    /**
     * Reject the request with given status. The call can be rejected for two
     * reasons: either the server is not running a compatible version of the
     * RPC protocol (RPC_MISMATCH), or the server rejects the identity of the
     * caller (AUTH_ERROR).
     *
     * @see RpcRejectStatus
     * @param status
     * @param reason
     */
    public void reject(int status, XdrAble reason) {
        XdrEncodingStream xdr = _xdr;
        try {
            RpcMessage replyMessage = new RpcMessage(_xid, RpcMessageType.REPLY);
            xdr.beginEncoding();
            replyMessage.xdrEncode(_xdr);
            xdr.xdrEncodeInt(RpcReplyStatus.MSG_DENIED);
            xdr.xdrEncodeInt(status);
            reason.xdrEncode(_xdr);
            xdr.endEncoding();

            _transport.send((Xdr)xdr);

        } catch (OncRpcException e) {
            _log.warn("Xdr exception: ", e);
        } catch (IOException e) {
            _log.error("Failed send reply: ", e);
        }
    }
    /**
     * Send accepted reply to the client.
     *
     * @param reply
     */
    public void reply(XdrAble reply) {
        acceptedReply(RpcAccepsStatus.SUCCESS, reply);
    }

    public void acceptedReply(int state, XdrAble reply) {

        XdrEncodingStream xdr = _xdr;
        try {
            RpcMessage replyMessage = new RpcMessage(_xid, RpcMessageType.REPLY);
            xdr.beginEncoding();
            replyMessage.xdrEncode(_xdr);
            xdr.xdrEncodeInt(RpcReplyStatus.MSG_ACCEPTED);
            _cred.getVerifier().xdrEncode(xdr);
            xdr.xdrEncodeInt(state);
            reply.xdrEncode(xdr);
            xdr.endEncoding();

            _transport.send((Xdr)xdr);

        } catch (OncRpcException e) {
            _log.warn("Xdr exception: ", e);
        } catch (IOException e) {
            _log.error("Failed send reply: ", e);
        }
    }

    /**
     * Retrieves the parameters sent within an ONC/RPC call message.
     *
     * @param args the call argument do decode
     * @throws OncRpcException
     */
    public void retrieveCall(XdrAble args) throws OncRpcException, IOException {
        args.xdrDecode(_xdr);
        _xdr.endDecoding();
    }

    /**
     * Reply to client with error program version mismatch.
     * Accepted message sent.
     *
     * @param min minimal supported version
     * @param max maximal supported version
     */
    public void failProgramMismatch(int min, int max) {
        acceptedReply(RpcAccepsStatus.PROG_MISMATCH, new MismatchInfo(min, max));
    }

    /**
     * Reply to client with error program unavailable.
     * Accepted message sent.
     */
    public void failProgramUnavailable() {
        acceptedReply(RpcAccepsStatus.PROG_UNAVAIL, XdrVoid.XDR_VOID);
    }

    /**
     * Reply to client with error procedure unavailable.
     */
    public void failProcedureUnavailable() {
        acceptedReply(RpcAccepsStatus.PROC_UNAVAIL, XdrVoid.XDR_VOID);
    }

    /**
     * Reply to client with error garbage args.
     */
    public void failRpcGarbage() {
        acceptedReply(RpcAccepsStatus.GARBAGE_ARGS, XdrVoid.XDR_VOID);
    }

    /**
     * Send asynchronous RPC request to a remove server.
     *
     * This method initiates an asynchronous RPC request. The handler parameter
     * is a completion handler that is invoked when the RPC operation completes
     * (or fails). The result passed to the completion handler is the RPC result
     * returned by server.
     *
     * @param procedure The number of the procedure.
     * @param args The argument of the procedure.
     * @param callback The completion handler.
     * @throws OncRpcException
     * @throws IOException
     * @since 2.4.0
     */
    public void call(int procedure, XdrAble args, CompletionHandler<RpcReply, XdrTransport> callback)
            throws OncRpcException, IOException {

        int xid = NEXT_XID.incrementAndGet();

        Xdr xdr = new Xdr(Xdr.MAX_XDR_SIZE);
        xdr.beginEncoding();
        RpcMessage rpcMessage = new RpcMessage(xid, RpcMessageType.CALL);
        rpcMessage.xdrEncode(xdr);
        xdr.xdrEncodeInt(RPCVERS);
        xdr.xdrEncodeInt(_prog);
        xdr.xdrEncodeInt(_version);
        xdr.xdrEncodeInt(procedure);
        _cred.xdrEncode(xdr);
        args.xdrEncode(xdr);
        xdr.endEncoding();

        if (callback != null) {
            _transport.getReplyQueue().registerKey(xid, callback);
        }
        _transport.send(xdr);
    }

    /**
     * Send asynchronous RPC request to a remove server.
     *
     * This method initiates an asynchronous RPC request. The method behaves in
     * exactly the same manner as the {@link #call(int, XdrAble, CompletionHandler)
     * method except that instead of specifying a completion handler, this method
     * returns a Future representing the pending result. The Future's get method
     * returns the RPC reply responded by server.
     *
     * @param <T> The result type of RPC call.
     * @param procedure The number of the procedure.
     * @param args The argument of the procedure.
     * @param type The expected type of the reply
     * @return A Future representing the result of the operation.
     * @throws OncRpcException
     * @throws IOException
     * @throws java.lang.IllegalAccessException if the class or its nullary
     * constructor is not accessible.
     * @throws java.lang.InstantiationException if this {@code type} represents an abstract class,
     * an interface, an array class, a primitive type, or void;
     * or if the class has no nullary constructor.
     * or if the instantiation fails for some other reason.
     * @since 2.4.0
     */
    public <T extends XdrAble> Future<T> call(int procedure, XdrAble args, final Class<T> type) throws OncRpcException, IOException,
            InstantiationException, IllegalAccessException {
        T result = type.newInstance();
        return getCallFuture(procedure, args, result);
    }

    /**
     * Send call to remove RPC server.
     *
     * @param procedure the number of the procedure.
     * @param args the argument of the procedure.
     * @param result the result of the procedure
     * @throws OncRpcException
     * @throws IOException
     */
    public void call(int procedure, XdrAble args, XdrAble result)
            throws OncRpcException, IOException {

        try {
            Future<XdrAble> future = getCallFuture(procedure, args, result);
            future.get();
        } catch (InterruptedException e) {
            // workaround missing chained constructor
            IOException ioe = new InterruptedIOException(e.getMessage());
            ioe.initCause(e);
            throw ioe;
        } catch (ExecutionException e) {
            Throwable t = Throwables.getRootCause(e);
            Throwables.propagateIfInstanceOf(t, OncRpcException.class);
            Throwables.propagateIfInstanceOf(t, IOException.class);

            throw new IOException(t);
        }
    }

    /**
     * Send call to remove RPC server.
     *
     * @param procedure the number of the procedure.
     * @param args the argument of the procedure.
     * @param result the result of the procedure
     * @param timeout
     * @throws OncRpcException
     * @throws IOException
     * @throws TimeoutException if the wait timed out
     */
    public void call(int procedure, XdrAble args, final XdrAble result, int timeout)
            throws OncRpcException, IOException, TimeoutException {

        try {
            Future<XdrAble> future = getCallFuture(procedure, args, result);
            future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // workaround missing chained constructor
            IOException ioe = new InterruptedIOException(e.getMessage());
            ioe.initCause(e);
            throw ioe;
        } catch (ExecutionException e) {
            Throwable t = Throwables.getRootCause(e);
            Throwables.propagateIfInstanceOf(t, OncRpcException.class);
            Throwables.propagateIfInstanceOf(t, IOException.class);

            throw new IOException(t);
        }
    }

    private <T extends XdrAble> Future<T> getCallFuture(int procedure, XdrAble args, final T result)
            throws OncRpcException, IOException {

        final SettableFuture<T> future = SettableFuture.create();
        CompletionHandler<RpcReply, XdrTransport> callback = new CompletionHandler<RpcReply, XdrTransport>() {

            @Override
            public void completed(RpcReply reply, XdrTransport attachment) {
                try {
                    reply.getReplyResult(result);
                    future.set(result);
                } catch (IOException e) {
                    failed(e, attachment);
                }
            }

            @Override
            public void failed(Throwable exc, XdrTransport attachment) {
                future.setException(exc);
            }
        };

        call(procedure, args, callback);
        return future;
    }
}
