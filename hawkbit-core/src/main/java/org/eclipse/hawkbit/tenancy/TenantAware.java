/**
 * Copyright (c) 2015 Bosch Software Innovations GmbH and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.tenancy;

import java.io.Serial;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.hawkbit.context.ContextAware;
import org.eclipse.hawkbit.auth.SpRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Utility class for actions that are aware of the application's current tenant.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TenantAware {

    // Note! There shall be no regular 'system'!
    public static final String SYSTEM_USER = "system";

    private static final Collection<? extends GrantedAuthority> SYSTEM_AUTHORITIES = List.of(new SimpleGrantedAuthority(SpRole.SYSTEM_ROLE));

    /**
     * Implementation might retrieve the current tenant from a session or thread-local.
     *
     * @return the current tenant
     */
   public static String getCurrentTenant() {
        final SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null) {
            final Object principal = context.getAuthentication().getPrincipal();
            if (context.getAuthentication().getDetails() instanceof TenantAwareAuthenticationDetails tenantAwareAuthenticationDetails) {
                return tenantAwareAuthenticationDetails.tenant();
            } else if (principal instanceof TenantAwareUser tenantAwareUser) {
                return tenantAwareUser.getTenant();
            }
        }
        return null;
    }

    /**
     * @return the username of the currently logged-in user
     */
    public static String getCurrentUsername() {
        final SecurityContext context = SecurityContextHolder.getContext();
        if (context.getAuthentication() != null) {
            final Object principal = context.getAuthentication().getPrincipal();
            if (principal instanceof OidcUser oidcUser) {
                return oidcUser.getPreferredUsername();
            }
            if (principal instanceof User user) {
                return user.getUsername();
            }
        }
        return null;
    }

    /**
     * Gives the possibility to run a certain code under a specific given {@code tenant}. Only the given {@link Callable} is executed
     * under the specific tenant e.g. under control of an {@link ThreadLocal}. After the {@link Callable} it must be ensured that the
     * original tenant before this invocation is reset.
     *
     * @param tenant the tenant which the specific code should run
     * @param callable the runner which is implemented to run this specific code under the given tenant
     * @return the return type of the {@link Callable}
     */
    @SuppressWarnings("java:S112") // java:S112 - it is generic class so a generic exception is fine
    public static <T> T runAsTenant(final String tenant, final Callable<T> callable) {
        return ContextAware.runInContext(buildUserSecurityContext(tenant, SYSTEM_USER, SYSTEM_AUTHORITIES), () -> {
            try {
                return callable.call();
            } catch (final RuntimeException e) {
                throw e;
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static SecurityContext buildUserSecurityContext(
            final String tenant, final String username, final Collection<? extends GrantedAuthority> authorities) {
        final SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new AuthenticationDelegate(
                SecurityContextHolder.getContext().getAuthentication(), tenant, username, authorities));
        return securityContext;
    }

    /**
     * An {@link Authentication} implementation to delegate to an existing {@link Authentication} object except setting the details
     * specifically for a specific tenant and user.
     */
    private static final class AuthenticationDelegate implements Authentication {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Authentication delegate;
        private final TenantAwareUser principal;
        private final TenantAwareAuthenticationDetails tenantAwareAuthenticationDetails;

        private AuthenticationDelegate(
                final Authentication delegate, final String tenant, final String username,
                final Collection<? extends GrantedAuthority> authorities) {
            this.delegate = delegate;
            principal = new TenantAwareUser(username, username, authorities, tenant);
            tenantAwareAuthenticationDetails = new TenantAwareAuthenticationDetails(tenant, false);
        }

        @Override
        public int hashCode() {
            return delegate != null ? delegate.hashCode() : -1;
        }

        @Override
        public boolean equals(final Object another) {
            if (another instanceof Authentication anotherAuthentication) {
                return Objects.equals(delegate, anotherAuthentication) &&
                        Objects.equals(principal, anotherAuthentication.getPrincipal()) &&
                        Objects.equals(tenantAwareAuthenticationDetails, anotherAuthentication.getDetails());
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return delegate != null ? delegate.toString() : null;
        }

        @Override
        public String getName() {
            return delegate != null ? delegate.getName() : null;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return delegate != null ? delegate.getAuthorities() : Collections.emptyList();
        }

        @Override
        public Object getCredentials() {
            return delegate != null ? delegate.getCredentials() : null;
        }

        @Override
        public Object getDetails() {
            return tenantAwareAuthenticationDetails;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }

        @Override
        public boolean isAuthenticated() {
            return delegate == null || delegate.isAuthenticated();
        }

        @Override
        public void setAuthenticated(final boolean isAuthenticated) {
            if (delegate == null) {
                return;
            }
            delegate.setAuthenticated(isAuthenticated);
        }
    }
}