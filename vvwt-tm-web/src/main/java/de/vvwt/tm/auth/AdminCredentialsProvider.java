package de.vvwt.tm.auth;

/**
 * Provides the bcrypt hash of the admin password after bootstrap.
 *
 * <p>This is the public API of the {@code auth} bounded context consumed by Spring Security and by
 * tests that need to authenticate as admin. Implemented by {@code
 * de.vvwt.tm.auth.internal.AdminCredentialsBootstrap}; wired by {@code
 * de.vvwt.tm.auth.internal.AuthConfiguration}.
 *
 * <p>Story E15S07 (atomic cutover): the legacy root-package classes were deleted at cutover; this
 * interface is the sole survivor in the root auth package alongside {@code package-info.java}. The
 * {@code ADMIN_USERNAME} constant is promoted here from the deleted legacy {@code SecurityConfig}
 * so that external consumers (tests, documentation) have a stable, non-internal reference.
 *
 * @since E05S02 (interface); E15S07 (ADMIN_USERNAME constant promoted here)
 */
public interface AdminCredentialsProvider {

    /**
     * Fixed admin username for the Tournament Manager V1 single-admin model.
     *
     * <p>Not configurable in V1 — one admin account per self-hosted instance. Promoted from the
     * deleted legacy root-package {@code SecurityConfig} at E15S07 so tests and documentation have
     * a stable, non-internal reference.
     */
    String ADMIN_USERNAME = "admin";

    /**
     * Returns the bcrypt hash of the admin password.
     *
     * @throws IllegalStateException if called before bootstrap has completed
     */
    String getPasswordHash();
}
