/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.clustering.tomcat.catalina;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.catalina.Context;
import org.wildfly.clustering.web.cache.session.FilteringHttpSession;
import org.wildfly.clustering.web.cache.session.ImmutableFilteringHttpSession;
import org.wildfly.clustering.web.session.ImmutableSession;

/**
 * Defines an action to be performed prior to destruction of a session.
 * @author Paul Ferraro
 */
public class CatalinaSessionDestroyAction implements Consumer<ImmutableSession> {
    private final Context context;

    public CatalinaSessionDestroyAction(Context context) {
        this.context = context;
    }

    @Override
    public void accept(ImmutableSession session) {
        FilteringHttpSession httpSession = new ImmutableFilteringHttpSession(session, this.context.getServletContext());
        HttpSessionEvent event = new HttpSessionEvent(httpSession);
        Stream.of(this.context.getApplicationLifecycleListeners()).filter(HttpSessionListener.class::isInstance).map(HttpSessionListener.class::cast).forEach(listener -> {
            try {
                this.context.fireContainerEvent("beforeSessionDestroyed", listener);
                listener.sessionDestroyed(event);
            } catch (Throwable e) {
                this.context.getLogger().warn(e.getMessage(), e);
            } finally {
                this.context.fireContainerEvent("afterSessionDestroyed", listener);
            }
        });
        for (Map.Entry<String, HttpSessionBindingListener> entry : httpSession.getAttributes(HttpSessionBindingListener.class).entrySet()) {
            HttpSessionBindingListener listener = entry.getValue();
            try {
                listener.valueUnbound(new HttpSessionBindingEvent(httpSession, entry.getKey(), listener));
            } catch (Throwable e) {
                this.context.getLogger().warn(e.getMessage(), e);
            }
        }
    }
}
