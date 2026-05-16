// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link TournamentLifecycleService} — RED-first per DEC-22 Iron Law.
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-LIFECYCLE-MARK-PLANNED-RED — {@code markPlanned}: DRAFT → PLANNED; invalid state
 *       throws {@link ConflictException}
 *   <li>AC-TEST-LIFECYCLE-ACTIVATE-RED — {@code activate}: PLANNED → ACTIVE; invalid state throws
 *       {@link ConflictException}
 *   <li>AC-TEST-LIFECYCLE-COMPLETE-RED — {@code complete}: ACTIVE → COMPLETED; invalid state throws
 *       {@link ConflictException}
 *   <li>AC-TEST-LIFECYCLE-CANCEL-RED — {@code cancel}: PLANNED → CANCELLED; ACTIVE → CANCELLED;
 *       invalid state (DRAFT/COMPLETED/CANCELLED) throws {@link ConflictException}
 * </ul>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test class is in {@code de.vvwt.tm.tournament} (the public API package) — cross-package
 * relative to the implementation {@code
 * de.vvwt.tm.tournament.internal.DefaultTournamentLifecycleService}. Per DEC-36, this test MUST
 * inject {@link TournamentLifecycleService} (the public interface), not the implementation class.
 *
 * <h2>Full context (DEC-38 Clause C analogy)</h2>
 *
 * <p>{@code @SpringBootTest} is required: the lifecycle service uses {@link
 * TournamentRepository#findByIdForUpdate(UUID)} which requires the full tenant-routing DataSource
 * stack (TenantContext + DataSourceRouter) and a real {@code @Transactional} boundary.
 *
 * @see TournamentLifecycleService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="DEC-37">DEC-37 — per-tournament pessimistic DB row-lock</a>
 * @see <a href="E48S03">E48S03 — Tournament lifecycle transitions</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:lifecycleserviceit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TournamentLifecycleService IT — E48S03 RED-first lifecycle transitions")
class TournamentLifecycleServiceIT {

    /** Subject: inject via interface per DEC-36 cross-package test typing rule. */
    @Autowired private TournamentLifecycleService lifecycleService;

    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        // Seed a locations row (FK tournament.location_id → locations.id, DEC-39 D2)
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "Lifecycle IT Location");

        // Create a DRAFT tournament for each test
        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "Lifecycle IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // markPlanned — AC-TEST-LIFECYCLE-MARK-PLANNED-RED
    // =========================================================================

    @Test
    @DisplayName("markPlanned: DRAFT → PLANNED sets status to PLANNED")
    void markPlanned_fromDraft_setsStatusPlanned() {
        Tournament result = lifecycleService.markPlanned(tournamentId);

        assertThat(result.getStatus()).isEqualTo("PLANNED");

        // Verify DB state independently
        String dbStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(dbStatus).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("markPlanned: non-DRAFT status throws ConflictException")
    void markPlanned_fromNonDraft_throwsConflictException() {
        // Set to PLANNED first
        jdbcTemplate.update("UPDATE tournament SET status = 'PLANNED' WHERE id = ?", tournamentId);

        assertThatThrownBy(() -> lifecycleService.markPlanned(tournamentId))
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // activate — AC-TEST-LIFECYCLE-ACTIVATE-RED
    // =========================================================================

    @Test
    @DisplayName("activate: PLANNED → ACTIVE sets status to ACTIVE")
    void activate_fromPlanned_setsStatusActive() {
        jdbcTemplate.update("UPDATE tournament SET status = 'PLANNED' WHERE id = ?", tournamentId);

        Tournament result = lifecycleService.activate(tournamentId);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");

        String dbStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(dbStatus).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("activate: non-PLANNED status throws ConflictException")
    void activate_fromNonPlanned_throwsConflictException() {
        // Tournament is DRAFT — not PLANNED
        assertThatThrownBy(() -> lifecycleService.activate(tournamentId))
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // complete — AC-TEST-LIFECYCLE-COMPLETE-RED
    // =========================================================================

    @Test
    @DisplayName("complete: ACTIVE → COMPLETED sets status to COMPLETED")
    void complete_fromActive_setsStatusCompleted() {
        jdbcTemplate.update("UPDATE tournament SET status = 'ACTIVE' WHERE id = ?", tournamentId);

        Tournament result = lifecycleService.complete(tournamentId);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");

        String dbStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(dbStatus).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("complete: non-ACTIVE status throws ConflictException")
    void complete_fromNonActive_throwsConflictException() {
        // Tournament is DRAFT — not ACTIVE
        assertThatThrownBy(() -> lifecycleService.complete(tournamentId))
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // cancel — AC-TEST-LIFECYCLE-CANCEL-RED
    // =========================================================================

    @Test
    @DisplayName("cancel: PLANNED → CANCELLED sets status to CANCELLED")
    void cancel_fromPlanned_setsStatusCancelled() {
        jdbcTemplate.update("UPDATE tournament SET status = 'PLANNED' WHERE id = ?", tournamentId);

        Tournament result = lifecycleService.cancel(tournamentId);

        assertThat(result.getStatus()).isEqualTo("CANCELLED");

        String dbStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(dbStatus).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel: ACTIVE → CANCELLED sets status to CANCELLED")
    void cancel_fromActive_setsStatusCancelled() {
        jdbcTemplate.update("UPDATE tournament SET status = 'ACTIVE' WHERE id = ?", tournamentId);

        Tournament result = lifecycleService.cancel(tournamentId);

        assertThat(result.getStatus()).isEqualTo("CANCELLED");

        String dbStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(dbStatus).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel: DRAFT status throws ConflictException (invalid transition)")
    void cancel_fromDraft_throwsConflictException() {
        // Tournament is DRAFT
        assertThatThrownBy(() -> lifecycleService.cancel(tournamentId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("cancel: COMPLETED status throws ConflictException (invalid transition)")
    void cancel_fromCompleted_throwsConflictException() {
        jdbcTemplate.update(
                "UPDATE tournament SET status = 'COMPLETED' WHERE id = ?", tournamentId);

        assertThatThrownBy(() -> lifecycleService.cancel(tournamentId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("cancel: CANCELLED status throws ConflictException (already cancelled)")
    void cancel_fromCancelled_throwsConflictException() {
        jdbcTemplate.update(
                "UPDATE tournament SET status = 'CANCELLED' WHERE id = ?", tournamentId);

        assertThatThrownBy(() -> lifecycleService.cancel(tournamentId))
                .isInstanceOf(ConflictException.class);
    }
}
