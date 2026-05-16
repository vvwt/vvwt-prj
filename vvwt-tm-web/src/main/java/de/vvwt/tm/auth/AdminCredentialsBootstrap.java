// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.auth;

import org.springframework.boot.ApplicationRunner;

/**
 * Bootstrap interface for admin credentials: runs at application startup to generate or load the
 * admin password, and exposes the bcrypt hash for authentication.
 *
 * <p>Implementations orchestrate generation → hashing → persistence of the admin password at
 * application startup, delegating each concern to dedicated collaborators.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component (including
 * {@code @Bean}-factory- produced {@code ApplicationRunner} beans) must have a public interface in
 * the bounded-context root package.
 *
 * @see de.vvwt.tm.auth.internal.DefaultAdminCredentialsBootstrap
 * @since E57S01 (DEC-58/DEC-72 interface extraction)
 */
public interface AdminCredentialsBootstrap extends ApplicationRunner {

    /**
     * Returns the bcrypt hash of the admin password after bootstrap has completed.
     *
     * @return the bcrypt hash
     * @throws IllegalStateException if called before bootstrap has completed
     */
    String getPasswordHash();
}
