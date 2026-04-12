package de.vvwt.tm.auth;

/**
 * Provides the bcrypt hash of the admin password after bootstrap.
 *
 * <p>Implemented by {@link AdminCredentialsBootstrap}. Consumed by {@link SecurityConfig}
 * to wire the {@code UserDetailsService} without coupling to the bootstrap implementation.
 *
 * @see AdminCredentialsBootstrap
 * @see SecurityConfig
 */
public interface AdminCredentialsProvider {

    /**
     * Returns the bcrypt hash of the admin password.
     *
     * @throws IllegalStateException if called before {@link AdminCredentialsBootstrap} has run
     */
    String getPasswordHash();
}
