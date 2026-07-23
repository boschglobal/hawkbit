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
import static org.eclipse.hawkbit.repository.jpa.management.JpaProviders.isEclipseLink;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import jakarta.persistence.EntityManagerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import org.eclipse.hawkbit.repository.DistributionSetManagement;
import org.eclipse.hawkbit.repository.jpa.AbstractJpaIntegrationTest;
import org.eclipse.hawkbit.repository.model.DistributionSet;
import org.eclipse.hawkbit.repository.model.DistributionSetType;
import org.eclipse.hawkbit.repository.model.SoftwareModuleType;
import org.eclipse.hawkbit.repository.test.util.QueryCount;
import org.eclipse.hawkbit.repository.test.util.QueryCountConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Root-cause reproduction for the "type relation query storm": loading {@link DistributionSet}s and navigating their
 * {@link DistributionSetType} re-queries the type and its module types on every persistence context, because ORM
 * relationship navigation bypasses the by-id Spring cache (that cache is only consulted via
 * {@code management.get()/find()}). With the EclipseLink L2 shared cache disabled for cluster safety, each resolution
 * hits the DB.
 * <p>
 * The real by-id cache is enabled here so we can prove the point: even with a warm type cache, per-entity navigation
 * still storms the DB. Query counting is provider-agnostic (JDBC layer), so this holds under EclipseLink and Hibernate.
 * The storm is NOT caused by ACM - this test runs without an access controller and still reproduces it.
 */
@Slf4j
@Import(QueryCountConfiguration.class)
@TestPropertySource(properties = {
        "hawkbit.cache.JpaDistributionSetType.spec=maximumSize=1000,expireAfterWrite=60s",
        "hawkbit.cache.JpaSoftwareModuleType.spec=maximumSize=1000,expireAfterWrite=60s",
        "logging.level.org.eclipse.hawkbit.repository.jpa.management.DistributionSetTypeRelationQueryTest=INFO" })
class DistributionSetTypeRelationQueryTest extends AbstractJpaIntegrationTest {

    private static final int MODULE_TYPES = 3;
    private static final int DISTRIBUTION_SETS = 8;

    @Autowired
    private QueryCount queryCount;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * One DS type (with {@value #MODULE_TYPES} module types) shared by {@value #DISTRIBUTION_SETS} distribution sets.
     * Warm the type cache, then process each DS in its OWN transaction (mimicking per-target/per-action handling) and
     * navigate {@code ds.getType()} module types.
     * <p>
     * Demonstrates the storm: even though the single shared DS type is already cached, navigation re-queries it and its
     * module types on every transaction - roughly {@code N} DS-type selects and {@code N * MODULE_TYPES}
     * module-type selects, i.e. the by-id cache does nothing for the relational path. This is the acceptance test for
     * the relation-overload fix: after the fix these counts should collapse to ~0 (served from the shared cache).
     */
    @Test
    void relationNavigationStormsDbDespiteWarmTypeCache() {
        final List<SoftwareModuleType> moduleTypes = new ArrayList<>();
        for (int i = 0; i < MODULE_TYPES; i++) {
            moduleTypes.add(testdataFactory.findOrCreateSoftwareModuleType("relStormSmType" + i));
        }
        final DistributionSetType dsType = testdataFactory.findOrCreateDistributionSetType(
                "relStormDsType", "rel storm ds type", moduleTypes, List.of());

        final List<Long> dsIds = new ArrayList<>();
        for (int i = 0; i < DISTRIBUTION_SETS; i++) {
            dsIds.add(distributionSetManagement.create(DistributionSetManagement.Create.builder()
                    .type(dsType).name("relStormDs" + i).version("1.0").build()).getId());
        }

        // warm the by-id caches for the shared type and its module types
        distributionSetTypeManagement.get(dsType.getId());

        final TransactionTemplate tx = new TransactionTemplate(txManager);
        queryCount.reset();
        for (final Long dsId : dsIds) {
            tx.executeWithoutResult(status -> {
                final DistributionSet ds = distributionSetManagement.get(dsId);
                // navigate the LAZY type + its EAGER elements -> each element's @ManyToOne smType
                ds.getType().getMandatoryModuleTypes().forEach(SoftwareModuleType::getKey);
            });
        }

        final long dsTypeSelects = queryCount.matching("sp_distribution_set_type");
        final long smTypeSelects = queryCount.matching("sp_software_module_type");

        logQueries("sp_distribution_set_type");
        logQueries("sp_software_module_type");

        // ROOT CAUSE: the shared, already-cached type is re-fetched per transaction, and its module types storm.
        // The smType storm is EclipseLink-only (per-element by-id resolution); Hibernate JOINs it in.
        assumeTrue(isEclipseLink(entityManagerFactory), "relation query storm is EclipseLink-specific");
        assertThat(dsTypeSelects)
                .as("DS-type re-queried per transaction despite warm cache (ORM navigation bypasses the by-id cache)")
                .isGreaterThanOrEqualTo(DISTRIBUTION_SETS);
        assertThat(smTypeSelects)
                .as("module types storm: ~N * MODULE_TYPES selects for a single shared, cached type")
                .isGreaterThanOrEqualTo((long) DISTRIBUTION_SETS * MODULE_TYPES);
    }

    /**
     * Contrast: the SAME type read {@value #DISTRIBUTION_SETS} times through {@code management.get(id)} is served from
     * the by-id cache after the first load - proving the cache works, but only for the direct-lookup path, not for ORM
     * relationship navigation (see {@link #relationNavigationStormsDbDespiteWarmTypeCache()}).
     */
    @Test
    void directTypeLookupsServedFromCache() {
        final DistributionSetType dsType = testdataFactory.findOrCreateDistributionSetType(
                "directLookupDsType", "direct lookup ds type",
                List.of(testdataFactory.findOrCreateSoftwareModuleType("directLookupSmType")), List.of());

        distributionSetTypeManagement.get(dsType.getId()); // warm

        queryCount.reset();
        for (int i = 0; i < DISTRIBUTION_SETS; i++) {
            distributionSetTypeManagement.get(dsType.getId());
        }
        assertThat(queryCount.selects())
                .as("repeated direct type lookups must be served from the by-id cache — 0 DB queries")
                .isZero();
    }

    /**
     * Dumps the distinct SQL touching {@code table} with how many times each was executed, so the storm is visible as
     * repeated identical (parameterized) SELECTs in the test log. Counts are what the assertions use; this shows the
     * actual requests behind them.
     */
    private void logQueries(final String table) {
        final Map<String, Integer> byStatement = new LinkedHashMap<>();
        final String needle = table.toLowerCase();
        queryCount.statements().stream()
                .filter(s -> s != null && s.toLowerCase().contains(needle))
                .forEach(s -> byStatement.merge(s, 1, Integer::sum));
        log.info("[{}] {} statement(s), {} distinct:", table,
                byStatement.values().stream().mapToInt(Integer::intValue).sum(), byStatement.size());
        byStatement.forEach((sql, count) -> log.info("  x{} {}", count, sql));
    }
}
