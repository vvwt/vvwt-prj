package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * RED-first IT for E55S06: {@link PhaseStatusChangedEvent} must still be published after
 * orchestrator step-B writes phase status (AC-IMPL-PHASE-STATUS-CHANGED-EVENT-PRESERVED,
 * AC-TEST-PHASE-STATUS-CHANGED-EVENT-STILL-FIRES-RED, DEC-64 D-13).
 *
 * <p>After E55S06 removes the event-driven pipeline, {@link PhaseStatusChangedEvent} must continue
 * to be published by {@link PhaseLifecycleService#transition()} — it is NOT deleted.
 *
 * <h2>DEC-22 RED-first governance</h2>
 *
 * <p>This test is GREEN from the start (PhaseStatusChangedEvent is published by
 * DefaultPhaseLifecycleService.transition() which is NOT modified by E55S06). The RED condition is:
 * if E55S06 accidentally deleted PhaseStatusChangedEvent or its publisher, this test would FAIL.
 * Written as a regression guard per DEC-64 D-13.
 *
 * @see PhaseLifecycleService
 * @see PhaseStatusChangedEvent
 * @see <a href="DEC-64">DEC-64 D-13 — PhaseStatusChangedEvent preserved</a>
 * @see <a href="E55S06">E55S06 — AC-IMPL-PHASE-STATUS-CHANGED-EVENT-PRESERVED</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phasestatuschangedit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@RecordApplicationEvents
@Import({TenantContextTestSupport.class})
@DisplayName(
        "PhaseStatusChangedEvent preserved after E55S06 migration"
                + " — AC-TEST-PHASE-STATUS-CHANGED-EVENT-STILL-FIRES-RED")
class PhaseStatusChangedEventPreservationIT {

    @Autowired private PhaseLifecycleService phaseLifecycleService;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEvents applicationEvents;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "PhaseStatusIT");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "PhaseStatusIT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                6,
                false);

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PENDING",
                false);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    /**
     * AC-TEST-PHASE-STATUS-CHANGED-EVENT-STILL-FIRES-RED: PhaseStatusChangedEvent is published by
     * PhaseLifecycleService.transition() and DomainEventBridge forwards it.
     *
     * <p>After E55S06 removes the event-driven pipeline, PhaseStatusChangedEvent must still fire
     * when the orchestrator calls transition() per DEC-64 D-13 (D-17 DEC-55 D-9 preserved).
     */
    @Test
    @DisplayName(
            "PhaseLifecycleService.transition() publishes PhaseStatusChangedEvent"
                    + " — AC-TEST-PHASE-STATUS-CHANGED-EVENT-STILL-FIRES-RED")
    void transition_publishesPhaseStatusChangedEvent() {
        phaseLifecycleService.transition(phaseId, Phase.PhaseStatus.PREPARED, "match-gen-done");

        // PhaseStatusChangedEvent must be published
        long eventCount = applicationEvents.stream(PhaseStatusChangedEvent.class).count();
        assertThat(eventCount)
                .as("PhaseStatusChangedEvent must be published by transition()")
                .isGreaterThanOrEqualTo(1L);
    }
}
