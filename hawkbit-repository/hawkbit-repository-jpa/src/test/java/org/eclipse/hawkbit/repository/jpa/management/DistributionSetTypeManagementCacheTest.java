/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.repository.jpa.management;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.hawkbit.repository.jpa.model.JpaDistributionSetType;
import org.eclipse.hawkbit.repository.model.DistributionSetType;
import org.junit.jupiter.api.Test;

/**
 * By-id cache hit/miss behaviour (no ACM) for the {@link DistributionSetType} management service.
 * <p>
 * Focus: prove that {@code get(id)} is served from the cache after the first load, so the repeated
 * DSType + N×SoftwareModuleType read storm happens once per entity instead of on every request.
 */
class DistributionSetTypeManagementCacheTest extends AbstractTypeManagementCacheTest {

    /**
     * Scenario: evict, then read the same DSType six times.
     * Proves: only the first read (cache miss) hits the DB; the next five are served from cache — 0 queries —
     * eliminating the repeated DSType read on every request.
     * <p/>
     * Note: the per-element {@code smType} by-id storm no longer fires even on a miss — {@code smType} is a lazy,
     * non-{@code @MapsId} association, so loading the type's elements reads the ids from their keys without resolving the
     * full {@link org.eclipse.hawkbit.repository.model.SoftwareModuleType} entities.
     */
    @Test
    void verifyRepeatedReadsOnlyMissHitsDb() {
        evict(JpaDistributionSetType.class.getSimpleName(), standardDsType.getId());

        final long beforeMiss = readQueries();
        distributionSetTypeManagement.get(standardDsType.getId()); // miss — hits DB
        assertThat(readQueries() - beforeMiss)
                .as("cache miss must load from DB")
                .isPositive();

        final long beforeHits = readQueries();
        for (int i = 0; i < 5; i++) {
            distributionSetTypeManagement.get(standardDsType.getId()); // all served from cache
        }
        assertThat(readQueries() - beforeHits)
                .as("repeated reads must be served from cache — 0 DB queries")
                .isZero();
    }

    /**
     * Scenario: load a DSType on a cold cache, then read its mandatory/optional module type IDS.
     * Proves: the ids come straight from each element's {@code @EmbeddedId} key, so reading them triggers no query - not
     * even the per-element {@code smType} by-id resolution (which is why {@code smType} is intentionally a plain lazy
     * {@code @ManyToOne}, not {@code @MapsId}). This is the query-free path the hot consumers ({@code isComplete},
     * {@code containsModuleType}) rely on. Full {@link org.eclipse.hawkbit.repository.model.SoftwareModuleType} entities
     * are deliberately lazy now; callers that need them resolve by id through the cached SoftwareModuleTypeManagement.
     * The default test DS type has 1 mandatory (os) and 2 optional (runtime, app) module types.
     */
    @Test
    void verifyCachedEntityModuleTypeIdsAccessibleWithoutQuery() {
        evict(JpaDistributionSetType.class.getSimpleName(), standardDsType.getId());
        final DistributionSetType loaded = distributionSetTypeManagement.get(standardDsType.getId());

        final long before = readQueries();
        assertThat(loaded.getMandatoryModuleTypeIds()).hasSize(1); // os
        assertThat(loaded.getOptionalModuleTypeIds()).hasSize(2); // runtime + app
        assertThat(readQueries() - before)
                .as("module type ids read from the element keys must not hit DB (no smType entity navigation)")
                .isZero();
    }
}
