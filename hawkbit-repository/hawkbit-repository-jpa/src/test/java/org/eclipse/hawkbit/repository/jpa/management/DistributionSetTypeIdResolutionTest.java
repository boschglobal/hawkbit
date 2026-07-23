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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceptance test for the {@code @MapsId} relation query storm fix: resolving a {@link DistributionSetType}'s mandatory
 * module type ids via {@code getMandatoryModuleTypeIds()} reads them straight from each element's {@code @EmbeddedId}
 * key, so navigating a freshly-loaded type does NOT fan out one {@code sp_software_module_type} by-id select per element.
 * <p>
 * Contrast with {@link DistributionSetTypeRelationQueryTest} / {@link DistributionSetTypeGraphQueryTest}, which show the
 * storm when the full {@link SoftwareModuleType} entities are navigated ({@code getMandatoryModuleTypes()}).
 * <p>
 * Query counting is provider-agnostic (JDBC layer). The assertion is the collapsed count, so it must hold under both
 * EclipseLink and Hibernate: at most a single element/type load touches {@code sp_software_module_type} (Hibernate joins
 * it in; EclipseLink loads the element rows only and reads the ids from the key) - never one-per-element.
 */
@Slf4j
@Import(QueryCountConfiguration.class)
class DistributionSetTypeIdResolutionTest extends AbstractJpaIntegrationTest {

    private static final int MODULE_TYPES = 5;

    @Autowired
    private QueryCount queryCount;

    @Autowired
    private PlatformTransactionManager txManager;

    /**
     * Navigate a freshly-loaded (cache-bypassing) DS type's mandatory module type ids and assert the id resolution does
     * not storm {@code sp_software_module_type}. Baseline (entity navigation) issues {@value #MODULE_TYPES} by-id selects
     * here; the fix collapses that to at most one.
     */
    @Test
    void mandatoryModuleTypeIdsDoNotStormModuleTypeTable() {
        final List<SoftwareModuleType> moduleTypes = new ArrayList<>();
        for (int i = 0; i < MODULE_TYPES; i++) {
            moduleTypes.add(testdataFactory.findOrCreateSoftwareModuleType("idResSmType" + i));
        }
        final DistributionSetType dsType = testdataFactory.findOrCreateDistributionSetType(
                "idResDsType", "id resolution ds type", moduleTypes, List.of());
        final Long dsId = distributionSetManagement.create(DistributionSetManagement.Create.builder()
                .type(dsType).name("idResDs").version("1.0").build()).getId();

        final TransactionTemplate tx = new TransactionTemplate(txManager);
        final Set<Long> ids = tx.execute(status -> {
            queryCount.reset();
            final DistributionSet ds = distributionSetManagement.get(dsId);
            // navigate the LAZY type (cache-bypassing) + its EAGER elements, then read the smType ids from the keys
            return ds.getType().getMandatoryModuleTypeIds();
        });

        logQueries("sp_software_module_type");

        assertThat(ids).hasSize(MODULE_TYPES);
        assertThat(queryCount.matching("sp_software_module_type"))
                .as("mandatory module type id resolution must not fan out one sp_software_module_type select per element")
                .isLessThanOrEqualTo(1L);
    }

    private void logQueries(final String table) {
        final String needle = table.toLowerCase();
        final List<String> matching = queryCount.statements().stream()
                .filter(s -> s != null && s.toLowerCase().contains(needle))
                .toList();
        log.info("[{}] {} statement(s):", table, matching.size());
        matching.forEach(sql -> log.info("  {}", sql));
    }
}
