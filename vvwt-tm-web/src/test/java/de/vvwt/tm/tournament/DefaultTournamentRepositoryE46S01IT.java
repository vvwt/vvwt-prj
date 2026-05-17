// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tournament.internal.DefaultTournamentRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DAO integration tests for E46S01 snapshot-at-INSERT logic in {@link DefaultTournamentRepository}.
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: the {@code organizer} column did not exist in the {@code
 * tournament} table (no V2 migration) and the snapshot SELECT logic was not present in {@link
 * DefaultTournamentRepository#save(Tournament)} — causing both compile error (missing {@code
 * organizer} column assertion) and runtime failure — satisfying the DEC-22 Iron Law.
 *
 * <h2>DEC-26 three-rule compliance (per DEC-46 scope extension)</h2>
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> {@link
 *       TenantDaoTestSupport#applyTournamentSchema(DataSource)} applies {@code tenant/V1}, {@code
 *       tenant/V2}, {@code tournament/V1}, {@code tournament/V2} via Spring {@code ScriptUtils}. No
 *       inline DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> all assertions on persisted state use
 *       {@code assertj-db} against the DataSource — never via {@link
 *       DefaultTournamentRepository#findById(UUID)}.
 *   <li><b>Rule 3 — Read/write decoupling:</b> pre-existing tournament rows are seeded via {@link
 *       TenantDaoTestSupport#insertDirectly(DataSource, String, Map)} — never via {@link
 *       DefaultTournamentRepository#save(Tournament)}.
 * </ol>
 *
 * <h2>Named algebraic invariants (DEC-41 §observable-form)</h2>
 *
 * <ul>
 *   <li>{@code organizer == tenants.display_name AT INSERT TIME}
 *   <li>{@code organizer is immutable on UPDATE}
 *   <li>{@code INSERT requires tenants row to exist}
 * </ul>
 *
 * <h2>Same-package DEC-36 exemption</h2>
 *
 * <p>This IT is in {@code de.vvwt.tm.tournament} — the same package as {@link
 * DefaultTournamentRepository}. The DEC-36 same-package exemption permits direct construction of
 * the impl class in this IT.
 *
 * @see DefaultTournamentRepository
 * @see TenantDaoTestSupport
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-41">DEC-41 — observable-form classification</a>
 * @see <a href="DEC-46">DEC-46 — DAO IT scope extension to all vvwt-prj modules</a>
 * @see <a href="E46S01">E46S01 — Data foundation for certificate i18n + organizer</a>
 */
@DisplayName("DefaultTournamentRepository — E46S01 snapshot-at-INSERT DAO IT")
class DefaultTournamentRepositoryE46S01IT {

    private DataSource dataSource;
    private DefaultTournamentRepository repository;
    private AssertDbConnection assertDb;

    private UUID locationId;

    @BeforeEach
    void setUp() {
        // Rule 1: fresh DataSource per test — no shared state
        dataSource = TenantDaoTestSupport.freshDataSource();
        // Rule 1: apply production Flyway migrations (V1 + V2 for both tenant and tournament)
        TenantDaoTestSupport.applyTournamentSchema(dataSource);
        // Rule 2: assertj-db connection for independent persistence verification
        assertDb = TenantDaoTestSupport.assertDbOf(dataSource);

        // Construct DefaultTournamentRepository directly (DEC-36 same-package exemption)
        repository = new DefaultTournamentRepository(new JdbcTemplate(dataSource));

        // Seed mandatory prerequisite: one locations row (FK tournament.location_id → locations.id)
        locationId = UUID.randomUUID();
        TenantDaoTestSupport.insertDirectly(
                dataSource, "locations", Map.of("id", locationId, "display_name", "Test Location"));
    }

    // =========================================================================
    // AC-INSERT-SNAPSHOT-FROM-TENANTS-DB + AC-DAO-IT-SEEDS-LOCATIONS-AND-TENANTS
    // Invariant: organizer == tenants.display_name AT INSERT TIME
    // =========================================================================

    @Test
    @DisplayName(
            "save() INSERT: organizer column populated from tenants.display_name"
                    + " (snapshot-at-INSERT)")
    void insertSnapshot_populatesOrganizerFromTenantsTable() {
        // GIVEN — seed tenants row with known display_name (Rule 3)
        UUID tenantId = UUID.randomUUID();
        TenantDaoTestSupport.insertDirectly(
                dataSource, "tenants", buildTenantsRow(tenantId, "Acme Sport Club"));

        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = buildTournament(tournamentId, locationId);

        // WHEN — save triggers INSERT branch (id not present)
        repository.save(tournament);

        // THEN — verify organizer column via assertj-db (Rule 2: never use repo.findById)
        Table table = assertDb.table("tournament").build();
        var rows = table.getRowsList();
        assertThat(rows).as("exactly one tournament row must exist after INSERT").hasSize(1);

        Object organizer = rows.get(0).getColumnValue("ORGANIZER").getValue();
        assertThat(organizer)
                .as("organizer must equal tenants.display_name at INSERT time")
                .isEqualTo("Acme Sport Club");
    }

    // =========================================================================
    // AC-UPDATE-DOES-NOT-RE-SNAPSHOT
    // Invariant: organizer is immutable on UPDATE
    // =========================================================================

    @Test
    @DisplayName("save() UPDATE: organizer column NOT re-snapshotted (immutable on UPDATE)")
    void update_doesNotReSnapshot_organizerRemains() {
        // GIVEN — pre-existing tournament row with organizer = "Original Club" (Rule 3)
        UUID tournamentId = UUID.randomUUID();
        var tournamentCols = new LinkedHashMap<String, Object>();
        tournamentCols.put("id", tournamentId);
        tournamentCols.put("location_id", locationId);
        tournamentCols.put("description", "Existing Tournament");
        tournamentCols.put("match_format", "BEST_OF_3");
        tournamentCols.put("scoring_rule_id", "setPoints");
        tournamentCols.put("set_validation_rule_id", "standardVolleyball");
        tournamentCols.put("match_generator_id", "roundRobin");
        tournamentCols.put("status", "DRAFT");
        tournamentCols.put("field_count", 2);
        tournamentCols.put("team_count", 4);
        tournamentCols.put("organizer", "Original Club");
        TenantDaoTestSupport.insertDirectly(dataSource, "tournament", tournamentCols);

        // Seed tenants row with DIFFERENT display_name — must NOT override organizer on UPDATE
        UUID tenantId = UUID.randomUUID();
        TenantDaoTestSupport.insertDirectly(
                dataSource, "tenants", buildTenantsRow(tenantId, "Renamed Club"));

        Tournament tournament = buildTournament(tournamentId, locationId);
        tournament.setDescription("Updated description");

        // WHEN — save triggers UPDATE branch (id already present)
        repository.save(tournament);

        // THEN — organizer must still be "Original Club" (Rule 2: assertj-db)
        Table table = assertDb.table("tournament").build();
        var rows = table.getRowsList();
        assertThat(rows).as("still exactly one tournament row after UPDATE").hasSize(1);

        Object organizer = rows.get(0).getColumnValue("ORGANIZER").getValue();
        assertThat(organizer)
                .as("organizer must remain 'Original Club' after UPDATE — NOT 'Renamed Club'")
                .isEqualTo("Original Club");
    }

    // =========================================================================
    // AC-INSERT-MISSING-TENANTS-ROW-FAIL-FAST
    // Invariant: INSERT requires tenants row to exist
    // =========================================================================

    @Test
    @DisplayName("save() INSERT: throws IllegalStateException when tenants table has no row")
    void insert_missingTenantsRow_throwsIllegalStateException() {
        // GIVEN — NO tenants row seeded (only locations row exists from setUp)
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = buildTournament(tournamentId, locationId);

        // WHEN + THEN — IllegalStateException thrown before any tournament row is written
        assertThatThrownBy(() -> repository.save(tournament))
                .as("INSERT must fail fast with IllegalStateException when no tenants row exists")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenants");

        // Verify no tournament row was written (Rule 2: assertj-db)
        Table table = assertDb.table("tournament").build();
        assertThat(table)
                .as("tournament table must remain empty when INSERT fails on missing tenants row")
                .hasNumberOfRows(0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Tournament buildTournament(UUID id, UUID locId) {
        Tournament t = new Tournament();
        t.setId(id);
        t.setLocationId(locId);
        t.setDescription("E46S01 Test Tournament");
        t.setMatchFormat("BEST_OF_3");
        t.setScoringRuleId("setPoints");
        t.setSetValidationRuleId("standardVolleyball");
        t.setMatchGeneratorId("roundRobin");
        t.setStatus("DRAFT");
        t.setCreatedAt(LocalDateTime.now());
        t.setFieldCount(2);
        t.setTeamCount(4);
        return t;
    }

    private Map<String, Object> buildTenantsRow(UUID id, String displayName) {
        // tenants table: id, display_name, tenant_location_count, is_default, created_at
        var cols = new LinkedHashMap<String, Object>();
        cols.put("id", id);
        cols.put("display_name", displayName);
        cols.put("tenant_location_count", 1);
        cols.put("is_default", true);
        return cols;
    }
}
