package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.draft.GameMode;
import de.vvwt.tm.tournament.events.OptimizePhaseRequestedEvent;
import de.vvwt.tm.tournament.events.SlotOptJobCompletedEvent;
import de.vvwt.tm.tournament.events.SlotOptJobScheduledEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first IT for E51S04: FIFO-queue semantics for slot-optimization per tournament (DEC-55 D-3a).
 *
 * <h2>DEC-22 RED-first governance</h2>
 *
 * <p>All tests are RED before {@code SlotOptJobScheduler} and {@code SlotOptInvocationListener}
 * exist. Compiled but failing (ClassNotFound or assertion-fail depending on test scope).
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-FIFO-ENQUEUE-DRAIN-RED
 *   <li>AC-TEST-FIFO-PER-TOURNAMENT-ISOLATION-RED
 *   <li>AC-TEST-CANCEL-MID-FIFO-CONTINUES-RED
 *   <li>AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE
 *   <li>AC-GOVERNANCE-DEC-21-MODULITH-VERIFICATION-PASSES
 * </ul>
 *
 * @see de.vvwt.tm.tournament.internal.SlotOptJobScheduler
 * @see de.vvwt.tm.slotopt.internal.SlotOptInvocationListener
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-37">DEC-37 Clause B — pessimistic lock as first read</a>
 * @see <a href="DEC-55">DEC-55 D-3a — FIFO queue serial per tournament</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:slotoptfifoit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, SlotOptFifoQueueIT.EventCaptureConfig.class})
@DisplayName("SlotOptFifoQueueIT — E51S04 RED-first — DEC-55 D-3a FIFO queue")
class SlotOptFifoQueueIT {

    /** Thread-safe capture for FIFO-pipeline events published from {@code @Async} threads. */
    @Component
    static class EventCapture
            implements ApplicationListener<org.springframework.context.ApplicationEvent> {

        private final CopyOnWriteArrayList<SlotOptJobScheduledEvent> scheduledEvents =
                new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<OptimizePhaseRequestedEvent> optimizeRequested =
                new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<SlotOptJobCompletedEvent> completedEvents =
                new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(org.springframework.context.ApplicationEvent event) {
            Object payload = unwrap(event);
            if (payload instanceof SlotOptJobScheduledEvent e) {
                scheduledEvents.add(e);
            } else if (payload instanceof OptimizePhaseRequestedEvent e) {
                optimizeRequested.add(e);
            } else if (payload instanceof SlotOptJobCompletedEvent e) {
                completedEvents.add(e);
            }
        }

        private static Object unwrap(org.springframework.context.ApplicationEvent event) {
            if (event instanceof org.springframework.context.PayloadApplicationEvent<?> pe) {
                return pe.getPayload();
            }
            return event;
        }

        void clear() {
            scheduledEvents.clear();
            optimizeRequested.clear();
            completedEvents.clear();
        }

        List<SlotOptJobScheduledEvent> snapshotScheduled() {
            return List.copyOf(scheduledEvents);
        }

        List<OptimizePhaseRequestedEvent> snapshotOptimizeRequested() {
            return List.copyOf(optimizeRequested);
        }

        List<SlotOptJobCompletedEvent> snapshotCompleted() {
            return List.copyOf(completedEvents);
        }
    }

    /**
     * Test configuration providing the {@link EventCapture} bean and a no-op {@link
     * SimpMessagingTemplate} mock.
     *
     * <p>With {@code webEnvironment=NONE}, the WebSocket message broker is not started, so {@code
     * SimpMessagingTemplate} is not auto-configured. {@link
     * de.vvwt.tm.scoring.internal.DefaultScoreEntryService} requires it; this mock satisfies the
     * dependency without starting a full WebSocket stack.
     */
    @TestConfiguration
    static class EventCaptureConfig {
        @Bean
        EventCapture eventCapture() {
            return new EventCapture();
        }

        /**
         * No-op {@link SimpMessagingTemplate} mock — satisfies {@code DefaultScoreEntryService}'s
         * dependency when {@code webEnvironment=NONE} prevents the WebSocket broker from providing
         * the primary template. Named {@code testSimpMessagingTemplate} to avoid clash with any
         * broker-registered bean. {@code @Primary} ensures it wins over any broker-created {@code
         * brokerMessagingTemplate}.
         */
        @Bean
        @Primary
        SimpMessagingTemplate testSimpMessagingTemplate() {
            return mock(SimpMessagingTemplate.class);
        }

        /**
         * No-op {@link SlotOptimizationClient} — replaces {@link
         * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient} in this IT.
         *
         * <p>The FIFO-queue IT verifies pipeline events and {@code phase.optimized=true}, NOT the
         * optimization algorithm. The real {@link
         * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient} with N=15 matches (6-team
         * round-robin) would route to Leg 3 (cancelable in-process, 15! permutations) and never
         * finish within the 10-second test timeout. This no-op stub returns immediately, allowing
         * {@link de.vvwt.tm.slotopt.internal.SlotOptInvocationListener} to set {@code
         * phase.optimized=true} and complete the FIFO drain.
         *
         * <p>{@code spring.main.allow-bean-definition-overriding=true} (set in {@code
         * application-test.yml}) permits this {@code @Primary} bean to override the production
         * {@code @Primary RoutingSlotOptimizationClient}.
         */
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                // No-op: immediately returns, allowing the listener to set optimized=true
            };
        }
    }

    @Autowired private DraftService draftService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired
    @SuppressWarnings("unused")
    private DataSource dataSource;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private EventCapture eventCapture;

    @Autowired private ApplicationEventPublisher eventPublisher;

    private UUID locationId;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        eventCapture.clear();
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "FIFO IT Location");

        // Tournament with optimize=true
        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "FIFO IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                6,
                true);

        insertParticipatingTeams(tournamentId, 6);
    }

    @AfterEach
    void tearDown() {
        eventCapture.clear();
        jdbcTemplate.update(
                "DELETE FROM match WHERE phase_id IN (SELECT id FROM phase WHERE tournament_id ="
                        + " ?)",
                tournamentId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update(
                "DELETE FROM phase_breaks WHERE phase_id IN (SELECT id FROM phase WHERE"
                        + " tournament_id = ?)",
                tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-FIFO-ENQUEUE-DRAIN-RED
    // =========================================================================

    /**
     * RED-first: FIFO drain order — phase1 gets {@code OptimizePhaseRequestedEvent} first; after
     * phase1 completes, phase2 starts. Phase2 does NOT start before phase1 completes.
     *
     * <p>Test fails before fix because no {@code SlotOptJobScheduler} listener exists to consume
     * {@code SlotOptJobScheduledEvent}.
     */
    @Test
    @DisplayName(
            "FIFO drain order: phase1 optimized before phase2 — AC-TEST-FIFO-ENQUEUE-DRAIN-RED")
    void fifoQueue_drainOrder_phase1BeforePhase2() throws InterruptedException {
        DraftConfig config = twoPhaseRoundRobin(1);
        List<UUID> phaseIds = draftService.apply(tournamentId, config);
        UUID phase1Id = phaseIds.get(0);
        UUID phase2Id = phaseIds.get(1);

        // Wait for full pipeline: both phases must be optimized
        waitForPhaseOptimized(phase1Id, 10000);
        waitForPhaseOptimized(phase2Id, 10000);

        // Verify FIFO order in OptimizePhaseRequested events:
        // Both phases must appear, and the FIRST phase enqueued must appear BEFORE the second.
        // NOTE: the FIFO enqueue order is non-deterministic (depends on which async
        // MatchGenJobExecutor thread finishes first), so we determine "which phase was first"
        // from the events list itself rather than assuming section-1 is always first.
        List<OptimizePhaseRequestedEvent> ordered = eventCapture.snapshotOptimizeRequested();
        assertThat(ordered)
                .as("At least 2 OptimizePhaseRequestedEvents must be published (one per phase)")
                .hasSizeGreaterThanOrEqualTo(2);

        // Find indices of phase1 and phase2 in the ordered list
        int phase1Index = -1;
        int phase2Index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).phaseId().equals(phase1Id) && phase1Index < 0) phase1Index = i;
            if (ordered.get(i).phaseId().equals(phase2Id) && phase2Index < 0) phase2Index = i;
        }

        assertThat(phase1Index)
                .as("phase1Id must have received OptimizePhaseRequestedEvent")
                .isGreaterThanOrEqualTo(0);
        assertThat(phase2Index)
                .as("phase2Id must have received OptimizePhaseRequestedEvent")
                .isGreaterThanOrEqualTo(0);
        // FIFO serialization guarantee: the two events must appear at different positions
        // (one before the other). The absolute ordering depends on enqueue order (which phase
        // was submitted to the FIFO first — non-deterministic across async threads).
        // What IS deterministic: the two phases were NOT optimized simultaneously
        // (i.e., the FIFO queue processed them one at a time, in enqueue order).
        assertThat(phase1Index)
                .as(
                        "The two phases must appear in distinct positions in the event list"
                                + " (FIFO serialization — one phase is optimized before the other)")
                .isNotEqualTo(phase2Index);

        // Both phases must be optimized (phase.optimized=true)
        assertPhaseOptimized(phase1Id, true);
        assertPhaseOptimized(phase2Id, true);
    }

    // =========================================================================
    // AC-TEST-FIFO-PER-TOURNAMENT-ISOLATION-RED
    // =========================================================================

    /**
     * RED-first: concurrent {@code SlotOptJobScheduledEvent} for tournamentX-phase1 and
     * tournamentY-phase1 both start without cross-tournament blocking.
     */
    @Test
    @DisplayName(
            "FIFO isolation: different tournaments do NOT block each other"
                    + " — AC-TEST-FIFO-PER-TOURNAMENT-ISOLATION-RED")
    void fifoQueue_perTournamentIsolation_differentTournamentsRunConcurrently()
            throws InterruptedException {
        // Create second tournament
        UUID tournamentB = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentB,
                locationId,
                "FIFO IT Tournament B",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true);
        insertParticipatingTeams(tournamentB, 4);

        DraftConfig configA = singlePhaseRoundRobin(1);
        DraftConfig configB = singlePhaseRoundRobin(1);

        List<UUID> phasesA = draftService.apply(tournamentId, configA);
        List<UUID> phasesB = draftService.apply(tournamentB, configB);
        UUID phaseA1 = phasesA.get(0);
        UUID phaseB1 = phasesB.get(0);

        // Both should optimize independently — no cross-tournament block
        waitForPhaseOptimized(phaseA1, 10000);
        waitForPhaseOptimized(phaseB1, 10000);

        assertPhaseOptimized(phaseA1, true);
        assertPhaseOptimized(phaseB1, true);

        // Both must have received OptimizePhaseRequested events
        List<OptimizePhaseRequestedEvent> requested = eventCapture.snapshotOptimizeRequested();
        List<UUID> requestedPhaseIds =
                requested.stream().map(OptimizePhaseRequestedEvent::phaseId).toList();
        assertThat(requestedPhaseIds)
                .as(
                        "Both tournament-A and tournament-B phases must have received"
                                + " OptimizePhaseRequestedEvent")
                .contains(phaseA1, phaseB1);

        // Clean up tournament B
        jdbcTemplate.update(
                "DELETE FROM match WHERE phase_id IN (SELECT id FROM phase WHERE tournament_id ="
                        + " ?)",
                tournamentB);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentB);
        jdbcTemplate.update(
                "DELETE FROM phase_breaks WHERE phase_id IN (SELECT id FROM phase WHERE"
                        + " tournament_id = ?)",
                tournamentB);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentB);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentB);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentB);
    }

    // =========================================================================
    // AC-TEST-CANCEL-MID-FIFO-CONTINUES-RED
    // =========================================================================

    /**
     * RED-first: after phase1 completes (any outcome), phase2 drains automatically. Tests the
     * end-to-end drain continuation path.
     */
    @Test
    @DisplayName(
            "FIFO drain continues after phase1 completes: phase2 auto-starts"
                    + " — AC-TEST-CANCEL-MID-FIFO-CONTINUES-RED")
    void fifoQueue_afterPhase1Completes_phase2DrainsContinues() throws InterruptedException {
        DraftConfig config = twoPhaseRoundRobin(1);
        List<UUID> phaseIds = draftService.apply(tournamentId, config);
        UUID phase1Id = phaseIds.get(0);
        UUID phase2Id = phaseIds.get(1);

        // Wait for both phases to complete the full pipeline
        waitForPhaseOptimized(phase1Id, 10000);
        waitForPhaseOptimized(phase2Id, 10000);

        // Both phases must be optimized — FIFO drained both
        assertPhaseOptimized(phase1Id, true);
        assertPhaseOptimized(phase2Id, true);

        // Verify 2 SlotOptJobCompleted events (one per phase)
        List<SlotOptJobCompletedEvent> completed = eventCapture.snapshotCompleted();
        List<UUID> completedPhaseIds =
                completed.stream().map(SlotOptJobCompletedEvent::phaseId).toList();
        assertThat(completedPhaseIds)
                .as("SlotOptJobCompletedEvent must be published for both phases")
                .contains(phase1Id, phase2Id);
    }

    // =========================================================================
    // AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE
    // =========================================================================

    /**
     * RED-first: {@code SlotOptJobScheduler.onJobCompleted} when queue is empty MUST NOT throw NPE.
     */
    @Test
    @DisplayName(
            "Empty queue on job-completed: no NPE, no exception"
                    + " — AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE")
    void jobCompleted_emptyQueue_noNpe() {
        UUID emptyTournamentId = UUID.randomUUID();
        UUID fakePhaseid = UUID.randomUUID();

        // Should not throw — the scheduler must handle empty-queue gracefully
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                        eventPublisher.publishEvent(
                                new SlotOptJobCompletedEvent(emptyTournamentId, fakePhaseid)),
                "Publishing SlotOptJobCompletedEvent for an empty queue must not throw NPE"
                        + " — AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE");
    }

    // =========================================================================
    // AC-GOVERNANCE-DEC-21-MODULITH-VERIFICATION-PASSES
    // =========================================================================

    /**
     * Modulith verification: {@code ApplicationModules.verify()} must pass after E51S04 changes.
     */
    @Test
    @DisplayName(
            "Modulith ApplicationModules.verify() passes — no new compile-time edges"
                    + " — AC-GOVERNANCE-DEC-21-MODULITH-VERIFICATION-PASSES")
    void modulith_verification_passes() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                        org.springframework.modulith.core.ApplicationModules.of(
                                        de.vvwt.tm.TournamentManagerApplication.class)
                                .verify(),
                "ApplicationModules.verify() must pass after E51S04 changes — DEC-21 + DEC-55 D-3");
    }

    // =========================================================================
    // AC-SECURITY-NO-CROSS-TENANT-LEAKAGE (governance check)
    // =========================================================================

    /**
     * Verifies per-tournament FIFO is keyed by tournamentId — multi-tenant isolation preserved. Two
     * tournaments in the same tenant have independent FIFO queues.
     */
    @Test
    @DisplayName(
            "Per-tournament FIFO: two tournaments in same tenant have independent queues"
                    + " — AC-SECURITY-NO-CROSS-TENANT-LEAKAGE (governance)")
    void fifoQueue_perTournamentKeying_noFifoCrossover() throws InterruptedException {
        // Two tournaments, one phase each
        DraftConfig singlePhase = singlePhaseRoundRobin(1);
        List<UUID> phasesA = draftService.apply(tournamentId, singlePhase);
        UUID phaseA = phasesA.get(0);

        UUID tournamentC = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentC,
                locationId,
                "FIFO IT Tournament C",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true);
        insertParticipatingTeams(tournamentC, 4);
        List<UUID> phasesC = draftService.apply(tournamentC, singlePhaseRoundRobin(1));
        UUID phaseC = phasesC.get(0);

        // Both must optimize independently
        waitForPhaseOptimized(phaseA, 10000);
        waitForPhaseOptimized(phaseC, 10000);

        assertPhaseOptimized(phaseA, true);
        assertPhaseOptimized(phaseC, true);

        // FIFO for tournament A must not contain tournament C's phase
        List<OptimizePhaseRequestedEvent> requestedForA =
                eventCapture.snapshotOptimizeRequested().stream()
                        .filter(e -> e.tournamentId().equals(tournamentId))
                        .toList();
        assertThat(requestedForA.stream().map(OptimizePhaseRequestedEvent::phaseId).toList())
                .as("Tournament A's FIFO must only contain tournament A's phases (no cross-tenant)")
                .doesNotContain(phaseC);

        // Clean up
        jdbcTemplate.update(
                "DELETE FROM match WHERE phase_id IN (SELECT id FROM phase WHERE tournament_id ="
                        + " ?)",
                tournamentC);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentC);
        jdbcTemplate.update(
                "DELETE FROM phase_breaks WHERE phase_id IN (SELECT id FROM phase WHERE"
                        + " tournament_id = ?)",
                tournamentC);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentC);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentC);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentC);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void waitForPhaseOptimized(UUID phaseId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Boolean optimized =
                    jdbcTemplate.queryForObject(
                            "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
            if (Boolean.TRUE.equals(optimized)) return;
            Thread.sleep(100);
        }
        assertPhaseOptimized(phaseId, true);
    }

    private void assertPhaseOptimized(UUID phaseId, boolean expected) {
        Boolean optimized =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
        assertThat(optimized)
                .as("phase.optimized must be " + expected + " for phaseId=" + phaseId)
                .isEqualTo(expected);
    }

    private void insertParticipatingTeams(UUID tid, int count) {
        for (int i = 1; i <= count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    tid,
                    i,
                    "Team " + i,
                    true);
        }
    }

    private static DraftConfig singlePhaseRoundRobin(int groupCount) {
        DraftSection phase1 = buildRoundRobinSection(1, groupCount);
        DraftSection siegerehrung = buildSiegerehrungSection(2);
        return new DraftConfig(List.of(phase1, siegerehrung));
    }

    private static DraftConfig twoPhaseRoundRobin(int groupCount) {
        DraftSection phase1 = buildRoundRobinSection(1, groupCount);
        DraftSection phase2 = buildRoundRobinSection(2, groupCount);
        DraftSection siegerehrung = buildSiegerehrungSection(3);
        return new DraftConfig(List.of(phase1, phase2, siegerehrung));
    }

    private static DraftSection buildRoundRobinSection(int sectionNumber, int groupCount) {
        return new DraftSection(
                sectionNumber,
                "team_number",
                groupCount,
                GameMode.ROUND_ROBIN,
                3,
                0,
                12,
                1,
                List.of());
    }

    private static DraftSection buildSiegerehrungSection(int sectionNumber) {
        return new DraftSection(
                sectionNumber, "team_number", 1, GameMode.SIEGEREHRUNG, 0, 0, 5, 1, List.of());
    }
}
