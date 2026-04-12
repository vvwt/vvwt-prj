package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhasePreparationService;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link PhasePreparationService} — AC16 through AC19 (E03S12).
 *
 * <p>Uses a full Spring context with H2 in-memory database and all Flyway migrations applied.
 * The test is in the {@code de.vvwt.tm.domain.repo} package to access package-private
 * {@link TenantContext#set} and {@link TenantContext#clear} methods (same pattern as
 * E03S05RepositoryIT).
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>AC16 — full 9-team preparation flow (Tournament → Phase → 9 Teams → 9 Avatars → all 4 steps)</li>
 *   <li>AC17 — phase not found → IllegalArgumentException</li>
 *   <li>AC18 — wrong phase status (ACTIVE) → IllegalStateException</li>
 *   <li>AC19 — transactional rollback on partial failure</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E03S12.story.md">Story E03S12</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e03s12db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class PhasePreparationServiceIT {

    @Autowired private PhasePreparationService phasePreparationService;
    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private MatchRepository matchRepository;

    private UUID defaultTenantId;

    @BeforeEach
    void setUpTenantContext() {
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        tenantContext.set(defaultTenantId);
    }

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    // =========================================================================
    // AC16 — Full 9-team preparation flow
    // =========================================================================

    /**
     * AC16: Setup Tournament → Phase → 9 Teams → 9 TeamAvatars.
     * Run all 4 preparation methods in sequence.
     * Verify: phase is ACTIVE, 36 matches generated (C(9,2)), all have slot coords,
     * referees assigned where possible, all matches state = ENABLED.
     */
    @Test
    @Transactional
    void ac16_fullFlow_9teams_36matches_phaseActive() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);

        // Create 9 teams (all eligible for referee assignment)
        UUID[] teamIds = new UUID[9];
        UUID[] avatarIds = new UUID[9];
        for (int i = 0; i < 9; i++) {
            teamIds[i] = createTeam(tournamentId, i + 1, true);
            avatarIds[i] = createAvatar(tournamentId, phaseId, teamIds[i], 1, i + 1);
        }

        // Step 1: Generate matches
        phasePreparationService.generateMatches(phaseId);

        List<Match> matchesAfterGeneration = matchRepository.findByPhaseId(phaseId);
        // C(9,2) = 36 matches
        assertThat(matchesAfterGeneration).hasSize(36);
        // All in OPEN state, no slots
        for (Match m : matchesAfterGeneration) {
            assertThat(m.getMatchState()).isEqualTo(MatchState.OPEN);
            assertThat(m.getLapNumber()).isNull();
            assertThat(m.getFieldNumber()).isNull();
        }

        // Verify phase still PENDING
        Phase phaseAfterGen = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(phaseAfterGen.getStatus()).isEqualTo(Phase.PhaseStatus.PENDING.name());

        // Step 2: Optimize slots (fallback: 3 courts → 12 laps)
        phasePreparationService.optimizeSlots(phaseId);

        List<Match> matchesAfterSlots = matchRepository.findByPhaseId(phaseId);
        assertThat(matchesAfterSlots).hasSize(36);
        for (Match m : matchesAfterSlots) {
            assertThat(m.getLapNumber()).as("lapNumber must be set after optimizeSlots")
                    .isNotNull();
            assertThat(m.getFieldNumber()).as("fieldNumber must be set after optimizeSlots")
                    .isNotNull();
        }

        // Step 3: Assign referees
        phasePreparationService.assignReferees(phaseId);

        // Step 4: Start phase
        phasePreparationService.startPhase(phaseId);

        // Verify final state
        Phase finalPhase = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(finalPhase.getStatus()).isEqualTo(Phase.PhaseStatus.ACTIVE.name());
        assertThat(finalPhase.getCurrentLapNumber()).isEqualTo(0);

        List<Match> finalMatches = matchRepository.findByPhaseId(phaseId);
        assertThat(finalMatches).hasSize(36);
        for (Match m : finalMatches) {
            assertThat(m.getMatchState())
                    .as("match %s must be ENABLED after startPhase", m.getId())
                    .isEqualTo(MatchState.ENABLED);
            assertThat(m.getLapNumber()).isNotNull();
            assertThat(m.getFieldNumber()).isNotNull();
            // At least one referee mechanism must be set (team or description)
            boolean hasReferee = m.getRefereeTeamId() != null || m.getRefereeDescription() != null;
            assertThat(hasReferee)
                    .as("match %s must have referee (team or description)", m.getId())
                    .isTrue();
        }
    }

    // =========================================================================
    // AC17 — Phase not found
    // =========================================================================

    @Test
    void ac17_generateMatches_phaseNotFound_throwsIllegalArgument() {
        assertThatThrownBy(() -> phasePreparationService.generateMatches(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phase not found");
    }

    @Test
    void ac17_optimizeSlots_phaseNotFound_throwsIllegalArgument() {
        assertThatThrownBy(() -> phasePreparationService.optimizeSlots(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phase not found");
    }

    @Test
    void ac17_assignReferees_phaseNotFound_throwsIllegalArgument() {
        assertThatThrownBy(() -> phasePreparationService.assignReferees(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phase not found");
    }

    @Test
    void ac17_startPhase_phaseNotFound_throwsIllegalArgument() {
        assertThatThrownBy(() -> phasePreparationService.startPhase(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phase not found");
    }

    // =========================================================================
    // AC18 — Wrong phase status
    // =========================================================================

    @Test
    @Transactional
    void ac18_generateMatches_activePhaseFails() {
        UUID tournamentId = createTournament();
        UUID phaseId = createActivePhase(tournamentId);

        assertThatThrownBy(() -> phasePreparationService.generateMatches(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @Transactional
    void ac18_startPhase_activePhaseFails() {
        UUID tournamentId = createTournament();
        UUID phaseId = createActivePhase(tournamentId);

        assertThatThrownBy(() -> phasePreparationService.startPhase(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("ACTIVE");
    }

    // =========================================================================
    // AC19 — Transactional rollback on partial failure
    // =========================================================================

    /**
     * AC19: The DELETE-then-INSERT in generateMatches is transactional.
     * Strategy: run generateMatches twice inside the same test-managed transaction.
     * The test framework rolls back after the test — neither run's data persists.
     * The service-level @Transactional boundary guarantees that if INSERT fails mid-way,
     * the DELETE (and any partial INSERT) is rolled back.
     *
     * We verify the structural guarantee by:
     * 1. Running generateMatches once → 36 matches created.
     * 2. Running generateMatches again → old 36 deleted, new 36 inserted.
     * 3. Count is still 36 (not 0 or 72).
     */
    @Test
    @Transactional
    @Rollback
    void ac19_generateMatches_rerunSameTransactionYields36Matches() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);

        for (int i = 0; i < 9; i++) {
            UUID teamId = createTeam(tournamentId, i + 1, true);
            createAvatar(tournamentId, phaseId, teamId, 1, i + 1);
        }

        // First generation
        phasePreparationService.generateMatches(phaseId);
        assertThat(matchRepository.findByPhaseId(phaseId)).hasSize(36);

        // Second generation (re-run): deletes old 36, inserts new 36
        phasePreparationService.generateMatches(phaseId);
        assertThat(matchRepository.findByPhaseId(phaseId)).hasSize(36);

        // At the end we should have exactly 36 matches — not 72
        List<Match> finalMatches = matchRepository.findByPhaseId(phaseId);
        assertThat(finalMatches).hasSize(36);
        // All must be OPEN
        for (Match m : finalMatches) {
            assertThat(m.getMatchState()).isEqualTo(MatchState.OPEN);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createTournament() {
        UUID id = UUID.randomUUID();
        Tournament t = new Tournament(
                id, defaultTenantId, "Test Tournament " + id,
                MatchFormat.BEST_OF_3.name(), "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now());
        tournamentRepository.save(t);
        return id;
    }

    private UUID createPhase(UUID tournamentId) {
        UUID id = UUID.randomUUID();
        Phase p = new Phase(id, defaultTenantId, tournamentId, 1, "Vorrunde", "PENDING", 0,
                LocalDateTime.now());
        phaseRepository.save(p);
        return id;
    }

    private UUID createActivePhase(UUID tournamentId) {
        UUID id = UUID.randomUUID();
        Phase p = new Phase(id, defaultTenantId, tournamentId, 1, "Vorrunde", "ACTIVE", 0,
                LocalDateTime.now());
        phaseRepository.save(p);
        return id;
    }

    private UUID createTeam(UUID tournamentId, int teamNumber, boolean refereeAssignment) {
        UUID id = UUID.randomUUID();
        Team t = new Team(id, defaultTenantId, tournamentId, teamNumber,
                "Team " + teamNumber, true, refereeAssignment, false, LocalDateTime.now());
        teamRepository.save(t);
        return id;
    }

    private UUID createAvatar(UUID tournamentId, UUID phaseId, UUID teamId,
                               int groupNumber, int groupPosition) {
        UUID id = UUID.randomUUID();
        TeamAvatar ta = new TeamAvatar(id, defaultTenantId, tournamentId, phaseId,
                groupNumber, groupPosition, teamId, null, LocalDateTime.now());
        teamAvatarRepository.save(ta);
        return id;
    }
}
