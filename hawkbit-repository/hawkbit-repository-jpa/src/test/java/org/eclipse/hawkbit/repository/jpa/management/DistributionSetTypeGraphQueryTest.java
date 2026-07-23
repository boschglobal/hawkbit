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

import org.eclipse.hawkbit.repository.SoftDeletedMode;
import org.eclipse.hawkbit.repository.jpa.AbstractJpaIntegrationTest;
import org.eclipse.hawkbit.repository.model.DistributionSetType;
import org.eclipse.hawkbit.repository.model.SoftwareModuleType;
import org.eclipse.hawkbit.repository.test.util.QueryCount;
import org.eclipse.hawkbit.repository.test.util.QueryCountConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

/**
 * Characterization test for the {@link DistributionSetType} relation query storm on the type listing (bulk read) path,
 * proving the by-id Spring cache does NOT cover it.
 * <p>
 * {@link org.eclipse.hawkbit.repository.jpa.model.JpaDistributionSetType#getElements() elements} is {@code EAGER} and
 * each {@link org.eclipse.hawkbit.repository.jpa.model.DistributionSetTypeElement#getSmType() element's smType} is a
 * {@code LAZY} {@code @ManyToOne}. With the EclipseLink L2 shared cache disabled for cluster safety, navigating the
 * {@code smType} entity of {@code N} distribution set types with {@code K} distinct module types each re-queries
 * {@code sp_software_module_type} by id per element - i.e. {@code N * K} selects. The by-id caches are enabled and
 * WARMED first; the storm persists anyway because ORM relationship navigation bypasses that cache (it is only consulted
 * via {@code management.get()/find()}). The storm materializes when {@code smType} is navigated (the {@code forEach}).
 * <p>
 * Query counting is provider-agnostic (JDBC layer), so this holds under EclipseLink and Hibernate.
 * <p>
 * NOTE: a {@code @NamedEntityGraph} does NOT collapse this under EclipseLink - EL forces the attributes to load but does
 * not fold the nested {@code smType} into a join. The provider-agnostic fix is a Criteria/JPQL {@code JOIN FETCH} on the
 * type read query, or reading only the ids from the element key (see {@code DistributionSetTypeElement#getSmTypeId}).
 * This test asserts the current storm; flip the assertion to the collapsed count once the fetch-join fix lands.
 */
@Slf4j
@Import(QueryCountConfiguration.class)
@TestPropertySource(properties = {
        "hawkbit.cache.JpaDistributionSetType.spec=maximumSize=1000,expireAfterWrite=60s",
        "hawkbit.cache.JpaSoftwareModuleType.spec=maximumSize=1000,expireAfterWrite=60s",
        "logging.level.org.eclipse.hawkbit.repository.jpa.management.DistributionSetTypeGraphQueryTest=INFO" })
class DistributionSetTypeGraphQueryTest extends AbstractJpaIntegrationTest {

    private static final int TYPES = 5;
    private static final int MODULE_TYPES_PER_TYPE = 4;

    @Autowired
    private QueryCount queryCount;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /**
     * Warm the by-id caches for all {@value #TYPES} distribution set types and their {@value #MODULE_TYPES_PER_TYPE}
     * distinct module types each, then list them via the RSQL path and resolve their mandatory module types. Despite
     * the warm cache, navigating the LAZY {@code smType} of each {@code element} re-queries
     * {@code sp_software_module_type} by id per element - {@code TYPES * MODULE_TYPES_PER_TYPE} selects - because ORM
     * navigation never consults the by-id cache.
     */
    @Test
    void listingStormsModuleTypeTableDespiteWarmByIdCache() {
        final List<Long> dsTypeIds = new ArrayList<>();
        final List<Long> smTypeIds = new ArrayList<>();
        for (int t = 0; t < TYPES; t++) {
            final List<SoftwareModuleType> mandatory = new ArrayList<>();
            for (int m = 0; m < MODULE_TYPES_PER_TYPE; m++) {
                final SoftwareModuleType smType = testdataFactory.findOrCreateSoftwareModuleType("graphSmType_" + t + "_" + m);
                smTypeIds.add(smType.getId());
                mandatory.add(smType);
            }
            dsTypeIds.add(testdataFactory.findOrCreateDistributionSetType(
                    "graphDsType_" + t, "graph ds type " + t, mandatory, List.of()).getId());
        }

        // warm the by-id caches for every type and module type - the storm must persist despite this
        dsTypeIds.forEach(id -> distributionSetTypeManagement.get(id));
        smTypeIds.forEach(id -> softwareModuleTypeManagement.get(id));

        queryCount.reset();
        final Page<? extends DistributionSetType> types = distributionSetTypeManagement.findByRsql(
                "key==graphDsType_*", SoftDeletedMode.EXCLUDE_SOFT_DELETED, PageRequest.of(0, 100));
        // navigating the LAZY smType entities triggers the per-element by-id resolution under EclipseLink
        types.forEach(type -> type.getMandatoryModuleTypes().forEach(SoftwareModuleType::getKey));

        logQueries("sp_distribution_set_type");
        logQueries("sp_software_module_type");

        assertThat(types).hasSize(TYPES);
        // the storm is EclipseLink-only (LAZY smType resolved by-id per element); Hibernate emits a JOIN and does not
        // storm. The collapsed behaviour is asserted provider-agnostically by the fix test.
        assumeTrue(isEclipseLink(entityManagerFactory), "relation query storm is EclipseLink-specific");
        assertThat(queryCount.matching("sp_software_module_type"))
                .as("listing bypasses the WARM by-id smType cache: ORM navigation re-queries each element's smType")
                .isGreaterThanOrEqualTo((long) TYPES * MODULE_TYPES_PER_TYPE);
    }

    /**
     * Dumps the distinct SQL touching {@code table} with how many times each was executed, so the storm is visible as
     * repeated identical (parameterized) SELECTs in the test log. Counts drive the assertions; this shows the actual
     * requests behind them.
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
