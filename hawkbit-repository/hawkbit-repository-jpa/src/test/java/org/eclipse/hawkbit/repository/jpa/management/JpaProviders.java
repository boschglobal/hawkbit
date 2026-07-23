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

import jakarta.persistence.EntityManagerFactory;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Provider detection for the smType relation-query-storm characterization tests only. The storm is EclipseLink-specific
 * (LAZY {@code smType} resolved by-id per element; Hibernate emits a JOIN and does not storm), so those tests
 * {@code assumeTrue(isEclipseLink(...))}. Deliberately local to these tests - production code and the shared test base
 * stay provider-agnostic.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class JpaProviders {

    static boolean isEclipseLink(final EntityManagerFactory entityManagerFactory) {
        return entityManagerFactory.getPersistenceUnitUtil().getClass().getName().toLowerCase().contains("eclipse");
    }
}
