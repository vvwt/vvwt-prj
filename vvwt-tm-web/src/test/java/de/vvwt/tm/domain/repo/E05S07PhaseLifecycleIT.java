package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhaseAuditLogEntry;
import de.vvwt.tm.domain.PhaseLifecycleService;
import de.vvwt.tm.domain.PhasePreparationResult;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.draft.DraftConfig;
import de.vvwt.tm.domain.draft.DraftSection;
import de.vvwt.tm.domain.DraftService;
import de.vvwt.tm.infrastructure.web.ConflictException;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for E05S07 — Phase lifecycle (prepare, start, advance-lap).
 *
 * <p>Uses the full Spring context with an in-memory H2 database and all Flyway migrations applied.
 * Tests verify end-to-end behavior of:
 * <ul>
 *   <li>AC1 — prepare phase runs steps 1–3</li>
 *   <li>AC4 — start phase PENDING → ACTIVE, tournament PLANNED → ACTIVE</li>
 *   <li>AC5 — advance lap: normal, unfinished-without-force (409), force writes audit</li>
 *   <li>AC2 — listPhases returns phases ordered by sequenceNumber</li>
 *   <li>V9 migration — phase_audit_log table is writable</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E05S07.story.md">Story E05S07</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s07db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Transactional
class E05S07PhaseLifecycleIT {

    @Autowired private PhaseLifecycleService phaseLifecycleService;
    @Autowired private DraftService draftService;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private PhaseAuditLogRepository phaseAuditLogRepository;
    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;

    private UUID tournamentId;
    private UUID defaultTenantId;

    @BeforeEach
    void setUp() {
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        tenantContext.set(defaultTenantId);

        // Create a DRAFT tournament (3 courts, 9 teams — beta venue size per story notes)
        Tournament tournament = new Tournament(
                UUID.randomUUID(), defaultTenantId,
                "IT Phase Lifecycle Test", "BEST_OF_1",
                "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now(), null, 3, 9);
        tournamentRepository.save(tournament);
        tournamentId = tournament.getId();

        // Create 9 participating teams
        for (int i = 1; i <= 9; i++) {
            Team team = new Team(UUID.randomUUID(), defaultTenantId, tournamentId,
                    i, "Team " + i, true, true, false, LocalDateTime.now());
            teamRepository.save(team);
        }

        // Apply a 1-section draft to create Phase 1 with TeamAvatars (dependency: E05S06)
        DraftSection section = new DraftSection(1, "team_number", 3, "roundrobin", 2, 5, 15, 1);
        DraftConfig config = new DraftConfig(List.of(section));
        draftService.saveDraft(tournamentId, config);
        draftService.applyDraft(tournamentId);  // Tournament → PLANNED, Phase 1 + TeamAvatars created
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // =========================================================================
    // V9 migration — phase_audit_log table exists and is writable
    // =========================================================================

    @Test
    void v9MigrationPhaseAuditLogTableIsWritable() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        assertThat(phases).isNotEmpty();
        UUID phaseId = phases.get(0).getId();

        PhaseAuditLogEntry entry = new PhaseAuditLogEntry(
                UUID.randomUUID(), defaultTenantId, phaseId,
                "FORCE_ADVANCE_LAP", 0, 2, "it-test", null);

        phaseAuditLogRepository.save(entry);

        List<PhaseAuditLogEntry> entries = phaseAuditLogRepository.findByPhaseId(phaseId);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo("FORCE_ADVANCE_LAP");
        assertThat(entries.get(0).getUnfinishedMatchCount()).isEqualTo(2);
    }

    // =========================================================================
    // AC1 — prepare phase runs steps 1–3
    // =========================================================================

    @Test
    void preparePhaseRunsAllThreeSteps() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        assertThat(phases).hasSize(1);
        UUID phaseId = phases.get(0).getId();

        PhasePreparationResult result = phaseLifecycleService.prepare(phaseId);

        assertThat(result.overallSuccess()).isTrue();
        assertThat(result.generateMatchesSuccess()).isTrue();
        assertThat(result.optimizeSlotsSuccess()).isTrue();
        assertThat(result.assignRefereesSuccess()).isTrue();

        // Matches must have been generated with slot coordinates
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        assertThat(matches).isNotEmpty();
        assertThat(matches).allMatch(m -> m.getLapNumber() != null && m.getFieldNumber() != null);
    }

    // =========================================================================
    // AC4 — start phase: PENDING → ACTIVE, tournament PLANNED → ACTIVE
    // =========================================================================

    @Test
    void startPhasePendingToActiveAndTournamentPlanningToActive() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        UUID phaseId = phases.get(0).getId();

        // Prepare first
        PhasePreparationResult prepResult = phaseLifecycleService.prepare(phaseId);
        assertThat(prepResult.overallSuccess()).isTrue();

        // Start
        Phase started = phaseLifecycleService.start(phaseId);

        assertThat(started.getStatus()).isEqualTo(Phase.PhaseStatus.ACTIVE.name());
        assertThat(started.getCurrentLapNumber()).isEqualTo(0);

        // Tournament must transition to ACTIVE
        Tournament tournament = tournamentRepository.findById(tournamentId).orElseThrow();
        assertThat(tournament.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void startPhase_withoutPreparation_throws409() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        UUID phaseId = phases.get(0).getId();

        // No matches generated yet — startPhase precondition fails
        assertThatThrownBy(() -> phaseLifecycleService.start(phaseId))
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // AC5 — advance lap
    // =========================================================================

    @Test
    void advanceLap_allTerminal_incrementsWithoutAudit() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        UUID phaseId = phases.get(0).getId();
        phaseLifecycleService.prepare(phaseId);
        phaseLifecycleService.start(phaseId);

        // Mark all matches in lap 0 as terminal (simulate completed lap)
        List<Match> lap0 = matchRepository.findByPhaseId(phaseId).stream()
                .filter(m -> m.getLapNumber() != null && m.getLapNumber() == 0)
                .toList();
        for (Match m : lap0) {
            m.setMatchState(MatchState.FINISHED_WINNER1);
            matchRepository.save(m);
        }

        // Advance lap (no force needed)
        Phase result = phaseLifecycleService.advanceLap(phaseId, false, null);

        assertThat(result.getCurrentLapNumber()).isEqualTo(1);
        assertThat(phaseAuditLogRepository.findByPhaseId(phaseId)).isEmpty();
    }

    @Test
    void advanceLap_unfinishedMatches_withoutForce_throws409() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        UUID phaseId = phases.get(0).getId();
        phaseLifecycleService.prepare(phaseId);
        phaseLifecycleService.start(phaseId);

        // Matches are ENABLED (not terminal) — unfinished
        assertThatThrownBy(() -> phaseLifecycleService.advanceLap(phaseId, false, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("unfinished");
    }

    @Test
    void advanceLap_unfinishedMatchesWithForce_writesAuditAndIncrements() {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        UUID phaseId = phases.get(0).getId();
        phaseLifecycleService.prepare(phaseId);
        phaseLifecycleService.start(phaseId);

        long unfinishedBefore = matchRepository.findByPhaseId(phaseId).stream()
                .filter(m -> m.getLapNumber() != null && m.getLapNumber() == 0)
                .filter(m -> m.getMatchState() == MatchState.ENABLED)
                .count();
        assertThat(unfinishedBefore).isGreaterThan(0);

        Phase result = phaseLifecycleService.advanceLap(phaseId, true, "organizer");

        // Lap incremented
        assertThat(result.getCurrentLapNumber()).isEqualTo(1);

        // Audit log written
        List<PhaseAuditLogEntry> auditEntries = phaseAuditLogRepository.findByPhaseId(phaseId);
        assertThat(auditEntries).hasSize(1);
        PhaseAuditLogEntry entry = auditEntries.get(0);
        assertThat(entry.getAction()).isEqualTo(PhaseLifecycleService.ACTION_FORCE_ADVANCE_LAP);
        assertThat(entry.getLapNumber()).isEqualTo(0);
        assertThat(entry.getUnfinishedMatchCount()).isEqualTo((int) unfinishedBefore);
        assertThat(entry.getActorId()).isEqualTo("organizer");
    }

    // =========================================================================
    // AC3 — listPhases: ordered by sequenceNumber
    // =========================================================================

    @Test
    void listPhasesReturnsOrderedBySequenceNumber() {
        List<Phase> phases = phaseLifecycleService.listPhases(tournamentId);

        assertThat(phases).isNotEmpty();
        for (int i = 1; i < phases.size(); i++) {
            assertThat(phases.get(i).getSequenceNumber())
                    .isGreaterThan(phases.get(i - 1).getSequenceNumber());
        }
    }
}
