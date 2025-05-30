/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.clustering.tomcat.catalina;

import java.time.Instant;
import java.util.function.Function;

import jakarta.servlet.http.HttpSession;

import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.wildfly.clustering.session.ImmutableSession;
import org.wildfly.clustering.session.spec.servlet.HttpSessionProvider;

/**
 * Provides default implementations of methods that can be derived or outright ignored.
 * @author Paul Ferraro
 */
public interface CatalinaSession extends Session, Function<ImmutableSession, HttpSession> {

	@Override
	default HttpSession apply(ImmutableSession session) {
		return HttpSessionProvider.INSTANCE.asSession(session, this.getManager().getContext().getServletContext());
	}

	@Override
	default long getCreationTimeInternal() {
		return this.getCreationTime();
	}

	@Override
	default void setCreationTime(long time) {
	}

	@Override
	default void setId(String id) {
	}

	@Override
	default void setId(String id, boolean notify) {
	}

	@Override
	default long getThisAccessedTime() {
		return this.getLastAccessedTime();
	}

	@Override
	default long getThisAccessedTimeInternal() {
		return this.getLastAccessedTime();
	}

	@Override
	default long getLastAccessedTimeInternal() {
		return this.getLastAccessedTime();
	}

	@Override
	default long getIdleTime() {
		return Instant.now().toEpochMilli() - this.getLastAccessedTime();
	}

	@Override
	default long getIdleTimeInternal() {
		return this.getIdleTime();
	}

	@Override
	default void setManager(Manager manager) {
	}

	@Override
	default void setNew(boolean isNew) {
	}

	@Override
	default void setValid(boolean isValid) {
	}

	@Override
	default void access() {
	}

	@Override
	default void recycle() {
	}
}
