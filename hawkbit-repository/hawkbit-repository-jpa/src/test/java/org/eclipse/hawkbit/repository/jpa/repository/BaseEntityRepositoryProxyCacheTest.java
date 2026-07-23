/**
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.repository.jpa.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.eclipse.hawkbit.repository.jpa.model.AbstractJpaBaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.concurrent.ConcurrentMapCache;

/**
 * Unit test for {@code BaseEntityRepositoryProxy#loadCached(Long)} - the private by-id cache path exercised through the
 * public {@link BaseEntityRepositoryProxy#findById(Long)}.
 * <p>
 * Uses a real {@link ConcurrentMapCache} plus a mock delegate so the exact number of {@code delegate.findById(id)}
 * invocations can be asserted. The proxy is built with a {@code null} {@code AccessController}, so {@code findById}
 * returns the {@code loadCached} result verbatim (no READ filtering interferes with the assertions).
 * <p>
 * These tests were written to settle two suspicions about {@code loadCached}:
 * <ol>
 * <li>that {@code delegate.findById} is called <em>twice</em> on a cache miss - it is not; the {@code map}/{@code orElseGet}
 * branches are mutually exclusive and {@link org.springframework.cache.Cache#get(Object, java.util.concurrent.Callable)}
 * invokes the value loader once;</li>
 * <li>that the {@code orElse(null)} in the value loader is unreachable - it is reachable: it maps an empty delegate
 * result to {@code null} so the cache can store/return a miss as {@link Optional#empty()}.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class BaseEntityRepositoryProxyCacheTest {

    private static final Long ID = 1L;

    @Mock
    @SuppressWarnings("unchecked")
    private BaseEntityRepository<AbstractJpaBaseEntity> delegate;

    private ConcurrentMapCache cache;
    private AbstractJpaBaseEntity entity;
    private BaseEntityRepositoryProxy<AbstractJpaBaseEntity> proxy;

    @BeforeEach
    void setUp() {
        cache = new ConcurrentMapCache("test");
        entity = mock(AbstractJpaBaseEntity.class);
        proxy = new BaseEntityRepositoryProxy<>(delegate, null);
    }

    /**
     * Cache miss with the entity present: the delegate is loaded exactly once (disproving the "called twice" suspicion)
     * and the loaded entity is returned.
     */
    @Test
    void cacheMissLoadsFromDelegateExactlyOnce() {
        when(delegate.getCache()).thenReturn(Optional.of(cache));
        when(delegate.findById(ID)).thenReturn(Optional.of(entity));

        assertThat(proxy.findById(ID)).contains(entity);

        verify(delegate, times(1)).findById(ID);
    }

    /**
     * A second read of the same id is served from the cache: the delegate is queried only on the first (miss) call,
     * never again.
     */
    @Test
    void secondReadIsServedFromCacheWithoutDelegateCall() {
        when(delegate.getCache()).thenReturn(Optional.of(cache));
        when(delegate.findById(ID)).thenReturn(Optional.of(entity));

        assertThat(proxy.findById(ID)).contains(entity); // miss
        assertThat(proxy.findById(ID)).contains(entity); // hit

        verify(delegate, times(1)).findById(ID);
    }

    /**
     * Cache miss with the entity absent: the delegate's empty result flows through the loader's {@code orElse(null)} to a
     * cached miss, and {@code findById} returns {@link Optional#empty()} after exactly one delegate call.
     */
    @Test
    void cacheMissWithAbsentEntityReturnsEmpty() {
        when(delegate.getCache()).thenReturn(Optional.of(cache));
        when(delegate.findById(ID)).thenReturn(Optional.empty());

        assertThat(proxy.findById(ID)).isEmpty();

        verify(delegate, times(1)).findById(ID);
    }

    /**
     * Caching disabled ({@code getCache()} empty): every call falls through the {@code orElseGet} branch to the delegate,
     * so two reads issue two delegate loads (no caching, no double-load per call either).
     */
    @Test
    void cachingDisabledAlwaysHitsDelegate() {
        when(delegate.getCache()).thenReturn(Optional.empty());
        when(delegate.findById(ID)).thenReturn(Optional.of(entity));

        assertThat(proxy.findById(ID)).contains(entity);
        assertThat(proxy.findById(ID)).contains(entity);

        verify(delegate, times(2)).findById(ID);
    }
}
