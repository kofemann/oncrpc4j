/*
 * Copyright (c) 2009 - 2014 Deutsches Elektronen-Synchroton,
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
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.FileSystemXmlApplicationContext;
import org.springframework.context.support.GenericGroovyApplicationContext;

public class SpringRunner {

    private final static String SVC_BEAN = "oncrpcsvc";

    private SpringRunner() {
        // this class it used only to bootstrap the Spring IoC
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: SpringRunner <config>");
            System.exit(1);
        }

        String configFile = args[0];
        try {
            ApplicationContext context;
            if (configFile.endsWith(".groovy")) {
                context = new GenericGroovyApplicationContext("file:" + configFile);
            } else {
                context = new FileSystemXmlApplicationContext(configFile);
            }
            OncRpcSvc service = (OncRpcSvc) context.getBean(SVC_BEAN);
            service.start();

            System.in.read();
        } catch (BeansException e) {
            System.err.println("Spring: " + e.getMessage());
            System.exit(1);
        }
    }
}
