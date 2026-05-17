// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant;

import java.util.UUID;
import javax.sql.DataSource;

/**
 * Per-tenant Flyway runner: applies module-ordered migrations to a single tenant's H2 database.
 *
 * <p>DEC-58 Clause A + DEC-72 Clause A-ext: every self-created Spring component — including
 * {@code @Bean}-factory-produced first-party service beans — must have a public interface in the
 * bounded-context root package.
 *
 * @see de.vvwt.tm.tenant.internal.DefaultPerTenantFlywayRunner
 * @see <a href="../../../../../../../../docs/governance/stories/E14S04.story.md">Story E14S04</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @since E57S05 (DEC-58/DEC-72 interface extraction)
 */
public interface PerTenantFlywayRunner {

    /**
     * Runs all pending Flyway migrations for the given tenant.
     *
     * <p>Resolves the tenant's DataSource, derives module-ordered migration locations, and invokes
     * Flyway. Subsequent calls with the same {@code tenantId} are idempotent — Flyway skips
     * already-applied migrations based on {@code flyway_schema_history} (AC5).
     *
     * @param tenantId the tenant for which to run migrations; must not be {@code null}
     * @throws TenantDataSourceResolver.UnknownTenantException if the tenant is not registered (AC6)
     * @throws IllegalArgumentException if {@code tenantId} is {@code null}
     * @throws org.flywaydb.core.api.FlywayException if a migration fails (AC4); propagated
     *     unchanged
     */
    void run(UUID tenantId);

    /**
     * Runs all pending Flyway migrations against the provided DataSource, using the same
     * module-ordered migration locations as {@link #run(UUID)}.
     *
     * <p>Subsequent calls with the same DataSource are idempotent — Flyway skips already-applied
     * migrations based on {@code flyway_schema_history}.
     *
     * @param tenantId the tenant UUID (used only for logging context); must not be {@code null}
     * @param dataSource the DataSource to run migrations against; must not be {@code null}
     * @throws IllegalArgumentException if {@code tenantId} or {@code dataSource} is {@code null}
     * @throws org.flywaydb.core.api.FlywayException if a migration fails; propagated unchanged
     */
    void runWithDataSource(UUID tenantId, DataSource dataSource);
}
