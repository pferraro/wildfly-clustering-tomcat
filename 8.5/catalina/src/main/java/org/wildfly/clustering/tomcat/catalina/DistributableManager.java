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

import java.io.IOException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.cache.batch.Batcher;
import org.wildfly.clustering.marshalling.Marshallability;
import org.wildfly.clustering.session.ImmutableSession;
import org.wildfly.clustering.session.Session;
import org.wildfly.clustering.session.SessionManager;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;

/**
 * Adapts a WildFly distributable SessionManager to Tomcat's Manager interface.
 * @author Paul Ferraro
 */
public class DistributableManager<B extends Batch> implements CatalinaManager<B> {
	private static final char ROUTE_DELIMITER = '.';

	private final SessionManager<CatalinaSessionContext, B> manager;
	private final Context context;
	private final Consumer<ImmutableSession> invalidateAction;
	private final Marshallability marshallability;
	private final String route;

	public DistributableManager(SessionManager<CatalinaSessionContext, B> manager, Context context, Marshallability marshallability) {
		this.manager = manager;
		this.marshallability = marshallability;
		this.context = context;
		this.route = ((Engine) context.getParent().getParent()).getJvmRoute();
		this.invalidateAction = new CatalinaSessionDestroyAction(context);
	}

	@Override
	public SessionManager<CatalinaSessionContext, B> getSessionManager() {
		return this.manager;
	}

	@Override
	public Marshallability getMarshallability() {
		return this.marshallability;
	}

	/**
	 * Strips routing information from requested session identifier.
	 */
	private static String parseSessionId(String requestedSesssionId) {
		int index = requestedSesssionId.indexOf(ROUTE_DELIMITER);
		return (index < 0) ? requestedSesssionId : requestedSesssionId.substring(0, index);
	}

	/**
	 * Appends routing information to session identifier.
	 */
	private org.apache.catalina.Session getSession(Session<CatalinaSessionContext> session) {
		String id = session.getId();
		String internalId = (this.route != null) ? new StringBuilder(id.length() + this.route.length() + 1).append(id).append(ROUTE_DELIMITER).append(this.route).toString() : id;
		return new DistributableSession<>(this, session, internalId, this.manager.getBatcher().suspendBatch(), () -> this.invalidateAction.accept(session));
	}

	@Override
	public void start() {
		this.manager.start();
	}

	@Override
	public void stop() {
		this.manager.stop();
	}

	@Override
	public org.apache.catalina.Session createSession(String sessionId) {
		String id = (sessionId != null) ? parseSessionId(sessionId) : this.manager.getIdentifierFactory().get();
		boolean close = true;
		Batcher<B> batcher = this.manager.getBatcher();
		// Batch will be closed by Session.close();
		B batch = batcher.createBatch();
		try {
			Session<CatalinaSessionContext> session = this.manager.createSession(id);
			HttpSessionEvent event = new HttpSessionEvent(HttpSessionProvider.INSTANCE.asSession(session, this.context.getServletContext()));
			Stream.of(this.context.getApplicationLifecycleListeners()).filter(HttpSessionListener.class::isInstance).map(HttpSessionListener.class::cast).forEach(listener -> {
				try {
					this.context.fireContainerEvent("beforeSessionCreated", listener);
					listener.sessionCreated(event);
				} catch (Throwable e) {
					this.context.getLogger().warn(e.getMessage(), e);
				} finally {
					this.context.fireContainerEvent("afterSessionCreated", listener);
				}
			});
			org.apache.catalina.Session result = this.getSession(session);
			close = false;
			return result;
		} catch (RuntimeException | Error e) {
			batch.discard();
			throw e;
		} finally {
			if (close) {
				batch.close();
			}
		}
	}

	@Override
	public org.apache.catalina.Session findSession(String sessionId) throws IOException {
		String id = parseSessionId(sessionId);
		boolean close = true;
		Batcher<B> batcher = this.manager.getBatcher();
		// Batch will be closed by Session.close();
		B batch = batcher.createBatch();
		try {
			Session<CatalinaSessionContext> session = this.manager.findSession(id);
			if (session == null) {
				return null;
			}
			org.apache.catalina.Session result = this.getSession(session);
			close = false;
			return result;
		} catch (RuntimeException | Error e) {
			batch.discard();
			throw e;
		} finally {
			if (close) {
				batch.close();
			}
		}
	}

	@Override
	public void changeSessionId(org.apache.catalina.Session session) {
		this.changeSessionId(session, this.manager.getIdentifierFactory().get());
	}

	@Override
	public void changeSessionId(org.apache.catalina.Session session, String id) {
		session.tellChangedSessionId(id, session.getId(), true, true);
	}

	@Override
	public Context getContext() {
		return this.context;
	}

	@Override
	public boolean willAttributeDistribute(String name, Object value) {
		return this.marshallability.isMarshallable(value);
	}
}
