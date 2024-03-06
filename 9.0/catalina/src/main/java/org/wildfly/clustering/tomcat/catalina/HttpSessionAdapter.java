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

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.wildfly.clustering.cache.batch.Batch;
import org.wildfly.clustering.cache.batch.BatchContext;
import org.wildfly.clustering.session.Session;

/**
 * Adapts a WildFly distributable Session to an HttpSession.
 * @author Paul Ferraro
 */
public class HttpSessionAdapter<B extends Batch> extends AbstractHttpSession {

	private static final Set<String> EXCLUDED_ATTRIBUTES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Globals.SUBJECT_ATTR, Globals.GSS_CREDENTIAL_ATTR, org.apache.catalina.valves.CrawlerSessionManagerValve.class.getName())));

	enum AttributeEventType implements BiConsumer<Context, HttpSessionBindingEvent> {
		ADDED("beforeSessionAttributeAdded", "afterSessionAttributeAdded", (listener, event) -> listener.attributeAdded(event)),
		REMOVED("beforeSessionAttributeRemoved", "afterSessionAttributeRemoved", (listener, event) -> listener.attributeRemoved(event)),
		REPLACED("beforeSessionAttributeReplaced", "afterSessionAttributeReplaced", (listener, event) -> listener.attributeReplaced(event)),
		;
		private final String beforeEvent;
		private final String afterEvent;
		private final BiConsumer<HttpSessionAttributeListener, HttpSessionBindingEvent> trigger;

		AttributeEventType(String beforeEvent, String afterEvent, BiConsumer<HttpSessionAttributeListener, HttpSessionBindingEvent> trigger) {
			this.beforeEvent = beforeEvent;
			this.afterEvent = afterEvent;
			this.trigger = trigger;
		}

		@Override
		public void accept(Context context, HttpSessionBindingEvent event) {
			Stream.of(context.getApplicationEventListeners()).filter(HttpSessionAttributeListener.class::isInstance).map(HttpSessionAttributeListener.class::cast).forEach(listener -> {
				try {
					context.fireContainerEvent(this.beforeEvent, listener);
					this.trigger.accept(listener, event);
				} catch (Throwable e) {
					context.getLogger().warn(e.getMessage(), e);
				} finally {
					context.fireContainerEvent(this.afterEvent, listener);
				}
			});
		}
	}

	private final AtomicReference<Session<CatalinaSessionContext>> session;
	private final CatalinaManager<B> manager;
	private final B batch;
	private final Runnable invalidateAction;
	private final Consumer<Session<CatalinaSessionContext>> closeIfInvalid;

	public HttpSessionAdapter(AtomicReference<Session<CatalinaSessionContext>> session, CatalinaManager<B> manager, B batch, Runnable invalidateAction, Consumer<Session<CatalinaSessionContext>> closeIfInvalid) {
		this.session = session;
		this.manager = manager;
		this.batch = batch;
		this.invalidateAction = invalidateAction;
		this.closeIfInvalid = closeIfInvalid;
	}

	@Override
	public boolean isNew() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return session.getMetaData().isNew();
		} catch (IllegalStateException e) {
			this.closeIfInvalid.accept(session);
			throw e;
		}
	}

	@Override
	public long getCreationTime() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return session.getMetaData().getCreationTime().toEpochMilli();
		} catch (IllegalStateException e) {
			this.closeIfInvalid.accept(session);
			throw e;
		}
	}

	@Override
	public long getLastAccessedTime() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return session.getMetaData().getLastAccessStartTime().toEpochMilli();
		} catch (IllegalStateException e) {
			this.closeIfInvalid.accept(session);
			throw e;
		}
	}

	@Override
	public int getMaxInactiveInterval() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return (int) session.getMetaData().getTimeout().getSeconds();
		} catch (IllegalStateException e) {
			this.closeIfInvalid.accept(session);
			throw e;
		}
	}

	@Override
	public void setMaxInactiveInterval(int interval) {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			session.getMetaData().setTimeout((interval > 0) ? Duration.ofSeconds(interval) : Duration.ZERO);
		} catch (IllegalStateException e) {
			this.closeIfInvalid.accept(session);
			throw e;
		}
	}

	@Override
	public void invalidate() {
		this.invalidateAction.run();
		Session<CatalinaSessionContext> session = this.session.get();
		try (BatchContext<B> context = this.manager.getSessionManager().getBatcher().resumeBatch(this.batch)) {
			try (B batch = context.getBatch()) {
				session.invalidate();
				this.closeIfInvalid.accept(session);
			}
		} catch (IllegalStateException e) {
			this.closeIfInvalid.accept(session);
			throw e;
		}
	}

	@Override
	public Object getAttribute(String name) {
		Session<CatalinaSessionContext> session = this.session.get();
		if (EXCLUDED_ATTRIBUTES.contains(name)) {
			return session.getContext().getNotes().get(name);
		}
		try {
			return session.getAttributes().get(name);
		} catch (IllegalStateException e) {
			this.closeIfInvalid.accept(session);
			throw e;
		}
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		Session<CatalinaSessionContext> session = this.session.get();
		try {
			return Collections.enumeration(session.getAttributes().keySet());
		} catch (IllegalStateException e) {
			this.closeIfInvalid.accept(session);
			throw e;
		}
	}

	@Override
	public void setAttribute(String name, Object value) {
		if (value != null) {
			Session<CatalinaSessionContext> session = this.session.get();
			if (EXCLUDED_ATTRIBUTES.contains(name)) {
				session.getContext().getNotes().put(name, value);
			} else {
				try {
					Object old = session.getAttributes().put(name, value);
					if (old != value) {
						this.notifySessionAttributeListeners(name, old, value);
					}
				} catch (IllegalStateException e) {
					this.closeIfInvalid.accept(session);
					throw e;
				}
			}
		} else {
			this.removeAttribute(name);
		}
	}

	@Override
	public void removeAttribute(String name) {
		Session<CatalinaSessionContext> session = this.session.get();
		if (EXCLUDED_ATTRIBUTES.contains(name)) {
			session.getContext().getNotes().remove(name);
		} else {
			try {
				Object value = session.getAttributes().remove(name);
				if (value != null) {
					this.notifySessionAttributeListeners(name, value, null);
				}
			} catch (IllegalStateException e) {
				this.closeIfInvalid.accept(session);
				throw e;
			}
		}
	}

	private void notifySessionAttributeListeners(String name, Object oldValue, Object newValue) {
		if (oldValue instanceof HttpSessionBindingListener) {
			HttpSessionBindingListener listener = (HttpSessionBindingListener) oldValue;
			try {
				listener.valueUnbound(new HttpSessionBindingEvent(this, name));
			} catch (Throwable e) {
				this.manager.getContext().getLogger().warn(e.getMessage(), e);
			}
		}
		if (newValue instanceof HttpSessionBindingListener) {
			HttpSessionBindingListener listener = (HttpSessionBindingListener) newValue;
			try {
				listener.valueBound(new HttpSessionBindingEvent(this, name));
			} catch (Throwable e) {
				this.manager.getContext().getLogger().warn(e.getMessage(), e);
			}
		}
		HttpSessionBindingEvent event = new HttpSessionBindingEvent(this, name, (oldValue != null) ? oldValue : newValue);
		AttributeEventType type = (oldValue == null) ? AttributeEventType.ADDED : (newValue == null) ? AttributeEventType.REMOVED : AttributeEventType.REPLACED;
		type.accept(this.manager.getContext(), event);
	}

	@Override
	public String getId() {
		return this.session.get().getId();
	}

	@Override
	public ServletContext getServletContext() {
		return this.manager.getContext().getServletContext();
	}
}
