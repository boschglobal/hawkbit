/**
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.repository.jpa.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import jakarta.persistence.OptimisticLockException;

import org.eclipse.hawkbit.exception.GenericSpServerException;
import org.eclipse.hawkbit.exception.SpServerError;
import org.eclipse.hawkbit.ql.QueryException;
import org.eclipse.hawkbit.repository.exception.ConcurrentModificationException;
import org.eclipse.hawkbit.repository.exception.EntityAlreadyExistsException;
import org.eclipse.hawkbit.repository.exception.InsufficientPermissionException;
import org.eclipse.hawkbit.repository.exception.RSQLParameterSyntaxException;
import org.eclipse.hawkbit.repository.exception.RSQLParameterUnsupportedFieldException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.transaction.TransactionSystemException;

class ExceptionMapperTest {

    @Test
    void duplicateKeyMappedToEntityAlreadyExists() {
        final DuplicateKeyException cause = new DuplicateKeyException("dup");

        assertMappedTo(cause, ExceptionMapper.mapRe(cause), EntityAlreadyExistsException.class);
    }

    @Test
    void optimisticLockMappedToConcurrentModification() {
        final OptimisticLockingFailureException cause = new OptimisticLockingFailureException("conflict");

        final RuntimeException result = ExceptionMapper.mapRe(cause);

        assertMappedTo(cause, result, ConcurrentModificationException.class);
        assertThat(((ConcurrentModificationException) result).getError())
                .isEqualTo(SpServerError.SP_REPO_CONCURRENT_MODIFICATION);
    }

    @Test
    void jpaOptimisticLockSubclassMappedToConcurrentModification() {
        // JpaOptimisticLockingFailureException is the actual subclass Spring/Hibernate throws at runtime;
        // MAPPED_EXCEPTION_ORDER isAssignableFrom catches it via the base-class mapping
        final JpaOptimisticLockingFailureException cause = new JpaOptimisticLockingFailureException(new OptimisticLockException("conflict"));

        assertMappedTo(cause, ExceptionMapper.mapRe(cause), ConcurrentModificationException.class);
    }

    @Test
    void accessDeniedMappedToInsufficientPermission() {
        final var cause = new AccessDeniedException("denied");

        assertMappedTo(cause, ExceptionMapper.mapRe(cause), InsufficientPermissionException.class);
    }

    // --- not-ordered EXCEPTION_MAPPING path (exact class name match) ---

    @Test
    void authorizationDeniedMappedToInsufficientPermission() {
        final var cause = new AuthorizationDeniedException("denied", (AuthorizationResult) () -> false);

        assertMappedTo(cause, ExceptionMapper.mapRe(cause), InsufficientPermissionException.class);
    }

    @Test
    void transactionSystemExceptionWithoutConstraintViolationReturnedAsIs() {
        final TransactionSystemException txEx = new TransactionSystemException("tx failed", new RuntimeException("other"));

        assertThat(ExceptionMapper.map(txEx)).isSameAs(txEx);
    }

    @Test
    void queryExceptionInvalidSyntaxMappedToRsqlSyntaxException() {
        final RuntimeException rootCause = new RuntimeException("root");
        final QueryException qe = new QueryException(QueryException.ErrorCode.INVALID_SYNTAX, "bad syntax", rootCause);

        assertMappedTo(rootCause, ExceptionMapper.map(qe), RSQLParameterSyntaxException.class);
    }

    @Test
    void queryExceptionUnsupportedFieldMappedToRsqlUnsupportedFieldException() {
        final RuntimeException rootCause = new RuntimeException("root");
        final QueryException qe = new QueryException(QueryException.ErrorCode.UNSUPPORTED_FIELD, "bad field", rootCause);

        assertMappedTo(rootCause, ExceptionMapper.map(qe), RSQLParameterUnsupportedFieldException.class);
    }

    @Test
    void queryExceptionGenericMappedToGenericSpServerException() {
        final var qe = new QueryException(QueryException.ErrorCode.GENERIC, "generic error");

        assertMappedTo(qe, ExceptionMapper.map(qe), GenericSpServerException.class);
    }

    @Test
    void unknownExceptionReturnedUnchanged() {
        final var unknown = new IllegalStateException("unexpected");

        assertThat(ExceptionMapper.mapRe(unknown)).isSameAs(unknown);
    }

    private static void assertMappedTo(final Exception expectedCause, final Exception actualResult, final Class<?> expectedType) {
        assertThat(actualResult)
                .isInstanceOf(expectedType)
                .hasCause(expectedCause);
    }
}
