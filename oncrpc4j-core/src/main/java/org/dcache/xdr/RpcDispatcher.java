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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;

public class RpcDispatcher extends BaseFilter {

    private final static Logger _log = LoggerFactory.getLogger(RpcDispatcher.class);
    /**
     * List of registered RPC services
     *
     */
    private final Map<OncRpcProgram, RpcDispatchable> _programs;

    private final ExecutorService _asyncExecutorService =
            Executors.newCachedThreadPool();

    /**
     * Create new RPC dispatcher for given program.
     *
     * @param programs {@link Map}
     *     with a mapping between program number and program
     *     handler.
     *
     * @throws NullPointerException if programs is null
     */
    public RpcDispatcher(Map<OncRpcProgram, RpcDispatchable> programs)
            throws NullPointerException {

        if (programs == null) {
            throw new NullPointerException("Programs is NULL");
        }

        _programs = programs;
    }

    @Override
    public NextAction handleRead(final FilterChainContext ctx) throws IOException {

        final RpcCall call = ctx.getMessage();
        final int prog = call.getProgram();
        final int vers = call.getProgramVersion();
        final int proc = call.getProcedure();

        _log.debug("processing request {}", call);

        _asyncExecutorService.execute( new Runnable() {

            @Override
            public void run() {
                RpcDispatchable program = _programs.get(new OncRpcProgram(prog, vers));
                if (program == null) {
                    call.failProgramUnavailable();
                } else {
                    try {
                        program.dispatchOncRpcCall(call);
                    } catch (RpcException e) {
                        call.reject(e.getStatus(), e.getRpcReply());
                        _log.error("Failed to process RPC request:", e);
                    } catch (IOException e) {
                        _log.error("Failed to process RPC request:", e);
                    } catch (OncRpcException e) {
                        _log.error("Failed to process RPC request:", e);
                    }
                }
            }
        });
        return ctx.getInvokeAction();
    }
}
