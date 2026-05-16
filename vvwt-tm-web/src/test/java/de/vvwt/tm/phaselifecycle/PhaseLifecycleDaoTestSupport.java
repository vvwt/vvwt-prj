// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;

/**
 * Per-module Poka-Yoke helper for {@code phaselifecycle} DAO integration tests (DEC-46 clause
 * #2(a)).
 *
 * <p>This module is {@code vvwt-tm-web} which uses DEC-20 DB-per-Tenant routing, so DEC-46 clause
 * #2(a) applies: {@link TenantDaoTestSupport} is delegated to for the core three rules. This
 * module-local helper adds phaselifecycle-specific convenience methods on top.
 *
 * <p>DEC-26 three rules:
 *
 * <ol>
 *   <li>Rule 1 — schema from production migration (loads V1 tournament chain + V4
 *       phase_lifecycle_job migration)
 *   <li>Rule 2 — assertj-db independent verifier via {@link TenantDaoTestSupport#assertDbOf}
 *   <li>Rule 3 — read/write decoupling via {@link TenantDaoTestSupport#insertDirectly}
 * </ol>
 *
 * <p>Design: {@code final} class, static methods only, no inheritance. API growth is story-gated
 * per DEC-26.
 *
 * <p>Location: {@code de.vvwt.tm.phaselifecycle} (module root package for test classes — note: the
 * note in E55S02 story specifies the DaoTestSupport lives at this location per DEC-46 §per-module
 * helper pattern).
 *
 * @since E55S02
 * @see <a href="../../../../../../../.gaai/project/contexts/memory/decisions/DEC-26.md">DEC-26</a>
 * @see <a href="../../../../../../../.gaai/project/contexts/memory/decisions/DEC-46.md">DEC-46</a>
 */
public final class PhaseLifecycleDaoTestSupport {

    private PhaseLifecycleDaoTestSupport() {
        // utility class — no instances
    }

    // -------------------------------------------------------------------------
    // Schema loading
    // -------------------------------------------------------------------------

    /**
     * Provisions a fresh in-memory H2 DataSource and applies the full schema chain required by
     * {@code phase_lifecycle_job} integration tests:
     *
     * <ol>
     *   <li>{@code auth/V1__admin_credentials.sql}
     *   <li>{@code tenant/V1__initial_schema.sql}
     *   <li>{@code tournament/V1__initial_schema.sql} (contains {@code phase} table — FK target)
     *   <li>{@code tournament/V3__phase_preparation_background_job_pipeline.sql}
     *   <li>{@code certificate/V1__initial_schema.sql}
     *   <li>{@code infoportal/V1__initial_schema.sql}
     *   <li>{@code tournament/V4__phase_lifecycle_job.sql} (the migration under test)
     * </ol>
     *
     * <p>DEC-26 Rule 1: all schema loaded from production Flyway migration files.
     *
     * @return a fresh DataSource with the complete schema applied
     * @throws IllegalStateException if any migration file is not found or fails to execute
     */
    public static DataSource freshDataSourceWithSchema() {
        DataSource ds = TenantDaoTestSupport.freshDataSource();
        // Load the full tournament schema chain (V1 through V3 + other modules)
        TenantDaoTestSupport.applyTournamentSchema(ds);
        // Load V4 — the migration under test (E55S02)
        TenantDaoTestSupport.applyMigration(
                ds, "db/migration/tournament/V4__phase_lifecycle_job.sql");
        return ds;
    }

    // -------------------------------------------------------------------------
    // Delegated DEC-26 Rule 2 + Rule 3
    // -------------------------------------------------------------------------

    /**
     * Returns an assertj-db {@link AssertDbConnection} for the given DataSource.
     *
     * <p>DEC-26 Rule 2: independent persistence verifier.
     *
     * @param ds the DataSource to inspect
     * @return AssertDbConnection for table-level assertions
     */
    public static AssertDbConnection assertDbOf(DataSource ds) {
        return TenantDaoTestSupport.assertDbOf(ds);
    }

    /**
     * Inserts a single row into the named table via direct JDBC.
     *
     * <p>DEC-26 Rule 3: read/write decoupling — use for fixture setup in read-path tests.
     *
     * @param ds DataSource to insert into
     * @param table target table name
     * @param cols column-name to value map
     */
    public static void insertDirectly(DataSource ds, String table, Map<String, Object> cols) {
        TenantDaoTestSupport.insertDirectly(ds, table, cols);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers for phase_lifecycle_job tests
    // -------------------------------------------------------------------------

    /**
     * Inserts a minimal {@code phase} row (the FK target for {@code phase_lifecycle_job.phase_id})
     * into the given DataSource.
     *
     * <p>Only the columns required by the phase table NOT NULL constraints are populated (per
     * {@code tournament/V1__initial_schema.sql}). Uses direct JDBC (DEC-26 Rule 3).
     *
     * @param ds DataSource with the tournament schema applied
     * @param phaseId the UUID to use as the phase's primary key
     * @param tournamentId the tournament this phase belongs to
     * @param sequenceNumber the phase sequence number within the tournament (must be unique per
     *     tournament)
     */
    public static void insertPhaseFixture(
            DataSource ds, UUID phaseId, UUID tournamentId, int sequenceNumber) {
        // phase table NOT NULL columns per tournament/V1 + V3:
        //   id, tournament_id, sequence_number, description, status, current_lap_number (DEFAULT 0)
        // V3 adds: optimized (DEFAULT FALSE), last_job_state (NULL)
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase",
                Map.of(
                        "id", phaseId,
                        "tournament_id", tournamentId,
                        "sequence_number", sequenceNumber,
                        "description", "Phase " + sequenceNumber,
                        "status", "PENDING"));
    }

    /**
     * Inserts a minimal {@code tournament} row required as FK target for {@code
     * phase.tournament_id}.
     *
     * <p>Required NOT NULL columns per {@code tournament/V1__initial_schema.sql}: {@code id,
     * location_id, description, match_format, scoring_rule_id, set_validation_rule_id,
     * match_generator_id}. Status defaults to 'DRAFT'; optimize defaults to TRUE (V3).
     *
     * @param ds DataSource with the tenant + tournament schema applied
     * @param tournamentId the UUID for the tournament's primary key
     * @param locationId the UUID of an existing {@code locations} row
     */
    public static void insertTournamentFixture(DataSource ds, UUID tournamentId, UUID locationId) {
        TenantDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "id", tournamentId,
                        "location_id", locationId,
                        "description", "Test Tournament",
                        "match_format", "BO1",
                        "scoring_rule_id", "default",
                        "set_validation_rule_id", "default",
                        "match_generator_id", "round-robin"));
    }

    /**
     * Inserts a minimal {@code locations} row required as FK target for {@code
     * tournament.location_id}.
     *
     * <p>Required NOT NULL columns per {@code tenant/V1__initial_schema.sql}: {@code id,
     * display_name}.
     *
     * @param ds DataSource with the tenant schema applied
     * @param locationId the UUID PK for the location
     */
    public static void insertLocationFixture(DataSource ds, UUID locationId) {
        TenantDaoTestSupport.insertDirectly(
                ds, "locations", Map.of("id", locationId, "display_name", "Test Location"));
    }
}
