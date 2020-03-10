/*
 * Copyright (c) 2009 - 2018 Deutsches Elektronen-Synchroton,
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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.dcache.oncrpc4j.xdr.Xdr;
import java.io.IOException;

import java.util.List;

public class RpcMessageParserTCP extends ByteToMessageDecoder {

    /**
     * RPC fragment record marker mask
     */
    private final static int RPC_LAST_FRAG = 0x80000000;
    /**
     * RPC fragment size mask
     */
    private final static int RPC_SIZE_MASK = 0x7fffffff;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf bb, List<Object> out) throws Exception {

        if (!isAllFragmentsArrived(bb)) {
            return;
        }

        out.add(assembleXdr(bb));
    }

    private boolean isAllFragmentsArrived(ByteBuf messageBuffer) throws IOException {
        final ByteBuf buffer = messageBuffer.duplicate();

        while (buffer.isReadable(4)) {

            int messageMarker = buffer.readInt();
            int size = getMessageSize(messageMarker);

            /*
             * fragment size bigger than we have received
             */
            if (!buffer.isReadable(size)) {
                return false;
            }

            /*
             * complete fragment received
             */
            if (isLastFragment(messageMarker)) {
                return true;
            }

            /*
             * seek to the end of the current fragment
             */
            buffer.readerIndex(buffer.readerIndex() + size);
        }

        return false;
    }

    private static int getMessageSize(int marker) {
        return marker & RPC_SIZE_MASK;
    }

    private static boolean isLastFragment(int marker) {
        return (marker & RPC_LAST_FRAG) != 0;
    }

    private Xdr assembleXdr(ByteBuf messageBuffer) {

        ByteBuf currentFragment;
        CompositeByteBuf multipleFragments = null;

        boolean messageComplete;
        do {
            int messageMarker = messageBuffer.readInt();

            int size = getMessageSize(messageMarker);
            messageComplete = isLastFragment(messageMarker);

            int pos = messageBuffer.readerIndex();
            currentFragment = messageBuffer.slice(pos, pos + size);
            currentFragment.capacity(size);

            messageBuffer.readerIndex(pos + size);
            if (!messageComplete & multipleFragments == null) {
                /*
                 * we use composite buffer only if required
                 * as they not for free.
                 */
                multipleFragments = Unpooled.compositeBuffer();
            }

            if (multipleFragments != null) {
                multipleFragments.addComponent(currentFragment);
            }
        } while (!messageComplete);

        return new Xdr(multipleFragments == null ? currentFragment : multipleFragments);
    }
}
