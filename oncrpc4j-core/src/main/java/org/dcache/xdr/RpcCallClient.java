/*
 * Copyright (c) 2009 - 2012 Deutsches Elektronen-Synchroton,
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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RpcCallClient {

    private final static Logger _log = LoggerFactory.getLogger(RpcCallClient.class);
    /**
     * XID number generator
     */
    private final static AtomicInteger NEXT_XID = new AtomicInteger(0);
    /**
     * Supported RPC protocol version
     */
    private final static int RPCVERS = 2;
    /**
     * Authentication credential.
     */
    private RpcAuth _cred;
    /**
     * RPC call transport.
     */
    private final XdrTransport _transport;

    public RpcCallClient(RpcAuth cred, XdrTransport transport) {
        _cred = cred;
        _transport = transport;
    }

    /**
     * Get RPC {
     *
     * @XdrTransport} used by this call.
     * @return transport
     */
    public XdrTransport getTransport() {
        return _transport;
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
    public void call(int program, int version, int procedure, XdrAble args, XdrAble result)
            throws OncRpcException, IOException {

        this.call(program, version, procedure, args, result, Integer.MAX_VALUE);
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
     */
    public void call(int program, int version, int procedure, XdrAble args,
            XdrAble result, int timeout)
            throws OncRpcException, IOException {

        int xid = NEXT_XID.incrementAndGet();

        Xdr xdr = new Xdr(Xdr.MAX_XDR_SIZE);
        xdr.beginEncoding();
        RpcMessage rpcMessage = new RpcMessage(xid, RpcMessageType.CALL);
        rpcMessage.xdrEncode(xdr);
        xdr.xdrEncodeInt(RPCVERS);
        xdr.xdrEncodeInt(program);
        xdr.xdrEncodeInt(version);
        xdr.xdrEncodeInt(procedure);
        _cred.xdrEncode(xdr);
        args.xdrEncode(xdr);
        xdr.endEncoding();

        _transport.getReplyQueue().registerKey(xid);
        _transport.send(xdr);

        RpcReply reply;
        try {
            reply = _transport.getReplyQueue().get(xid, timeout);
            if (reply == null) {
                _log.info("Did not get reply in time");
                throw new IOException("Did not get reply in time");
            }
        } catch (InterruptedException e) {
            _log.error("call processing interrupted");
            throw new IOException(e.getMessage());
        }

        if (reply.isAccepted() && reply.getAcceptStatus() == RpcAccepsStatus.SUCCESS) {
            reply.getReplyResult(result);
        } else {
            _log.info("reply not succeeded {}", reply);
            // FIXME: error handling here

            if (reply.isAccepted()) {
                throw new OncRpcAcceptedException(reply.getAcceptStatus());
            }
            throw new OncRpcRejectedException(reply.getRejectStatus());
        }
    }
}
