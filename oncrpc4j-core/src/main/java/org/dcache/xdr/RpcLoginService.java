/*
 * Copyright (c) 2009 - 2017 Deutsches Elektronen-Synchroton,
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

import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import javax.security.auth.Subject;

/**
 * Implementors of this interface map credentials provided by the RPC
 * request to a {@link Subject} which have to be used to authorize the
 * request.
 * This is typically used to map Kerberos principal to a UNIX user record with
 * uid, primary gid and array of secondary gids.
 */
public interface RpcLoginService {

    /**
     * Login service, which returns a Subject without any principals.
     */
    public static RpcLoginService NOP_LOGIN_SERVICE = (XdrTransport transport, Set<Principal> in)
            -> new Subject(true, Collections.EMPTY_SET, Collections.EMPTY_SET, Collections.EMPTY_SET);

    /**
     * Login service, which returns a Subject with all provided principals.
     */
    public static RpcLoginService DEFAULT_LOGIN_SERVICE = (XdrTransport transport, Set<Principal> in)
            -> new Subject(true, in, Collections.EMPTY_SET, Collections.EMPTY_SET);


    /**
     * Get subjects which should be used with the request.
     * @param transport associated with the request.
     * @param principals principals provided by RPC request.
     * @return subjects which must be used to authorize the request.
     */
    Subject login(XdrTransport transport, Set<Principal> principals);
}
