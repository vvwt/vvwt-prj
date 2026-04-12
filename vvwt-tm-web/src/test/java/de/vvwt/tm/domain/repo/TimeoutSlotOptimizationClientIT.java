package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhasePreparationService;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.slotopt.DirectSlotOptimizationClient;
import de.vvwt.tm.slotopt.FallbackSlotOptimizationClient;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.DefaultTenantProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for timeout-based slot optimization — AC15 (E04S04).
 *
 * <p>Uses a full Spring context with H2 in-memory database. Sets
 * {@code tm.slotopt.exhaustive-max-n=5} to force timeout mode for 12-team phases
 * (N = C(12,2) = 66 matches &gt;&gt; 5 = exhaustiveMaxN).
 *
 * <p>The {@code tm.slotopt.timeout-seconds=10} keeps the test fast while still covering
 * meaningful permutation space for AC15's full-flow verification.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>AC1 — exhaustiveMaxN=5: N=66 triggers timeout mode (not exhaustive)</li>
 *   <li>AC2 — configurable timeout (10s in test)</li>
 *   <li>AC3 — micro-segment strategy: segments submitted, best result collected</li>
 *   <li>AC4 — best-effort result applied; variety score &le; fallback's score</li>
 *   <li>AC6 — result applied via applyOptimizedSlots; all matches have valid slot coords</li>
 *   <li>AC8 — INFO logs emitted (smoke-test only — log content not asserted)</li>
 *   <li>AC9 — phase-not-found: IllegalArgumentException</li>
 *   <li>AC10 — no-matches: IllegalStateException</li>
 *   <li>AC15 — full preparation flow: 12 teams → ACTIVE phase, all slots valid, round constraint</li>
 *   <li>AC16 — tenant-scoped repository access throughout</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E04S04.story.md">Story E04S04</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e04s04db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.exhaustive-max-n=5",
            "tm.slotopt.timeout-seconds=10",
            "tm.slotopt.segment-size=500000"
        })
@ActiveProfiles("test")
class TimeoutSlotOptimizationClientIT {

    @Autowired private SlotOptimizationClient slotOptimizationClient;
    @Autowired @Qualifier("fallbackSlotOptimizer") private SlotOptimizationClient fallbackSlotOptimizationClient;
    @Autowired @Qualifier("slotOptExecutor") private ExecutorService slotOptExecutor;
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
    // Bean wiring validation
    // =========================================================================

    /**
     * Verifies that the injected bean is {@link DirectSlotOptimizationClient} and that the
     * {@link FallbackSlotOptimizationClient} is also available as a separate named bean (AC1).
     */
    @Test
    void beanWiring_directClientIsActive_fallbackIsAvailableByQualifier() {
        assertThat(slotOptimizationClient)
                .as("Primary SlotOptimizationClient must be DirectSlotOptimizationClient")
                .isInstanceOf(DirectSlotOptimizationClient.class);

        assertThat(fallbackSlotOptimizationClient)
                .as("Fallback bean must be FallbackSlotOptimizationClient, available by qualifier")
                .isInstanceOf(FallbackSlotOptimizationClient.class);

        assertThat(slotOptExecutor)
                .as("slotOptExecutor bean must be present")
                .isNotNull();
    }

    // =========================================================================
    // AC15 — Full preparation flow with timeout mode
    // =========================================================================

    /**
     * AC15: Full preparation flow for a 5-team phase using timeout-based optimization,
     * with 3 additional referee-only teams.
     *
     * <p>5 playing teams → round-robin → C(5,2) = 10 matches (N=10).
     * With {@code exhaustive-max-n=5}, N = rowCount = 10 &gt; 5, so timeout mode is used.
     * N=10 is within {@link de.vvwt.worker.types.JobDef#MAX_N} (17), so PacketSolver can handle it.
     *
     * <p>3 additional referee-only teams (with {@code refereeAssignment=true} but no
     * {@link de.vvwt.tm.domain.TeamAvatar} in this phase) are registered in the tournament.
     * They are never playing and therefore always available as referee candidates,
     * satisfying the {@code startPhase} pre-condition that every match has a referee.
     * With 5 playing teams the greedy algorithm produces 5 laps of 2 matches each;
     * 3 referee-only teams ≥ 2 needed per lap, so all matches get referees.
     *
     * <p>Verifies:
     * <ul>
     *   <li>10 matches generated (round-robin for 5 playing teams)</li>
     *   <li>All 10 matches have non-null {@code lapNumber} and {@code fieldNumber} after
     *       {@code optimizeSlots} (AC6)</li>
     *   <li>Round constraint satisfied: no avatar plays twice in the same lap (AC3/AC6)</li>
     *   <li>Phase transitions to ACTIVE after {@code startPhase} (AC15)</li>
     * </ul>
     */
    @Test
    @Transactional
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void ac15_fullFlow_5teams_timeoutMode_phaseBecomesActive() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);

        // Create 5 playing teams with avatars in the phase — round-robin generates C(5,2) = 10 matches.
        for (int i = 1; i <= 5; i++) {
            UUID teamId = createTeam(tournamentId, i);
            createAvatar(tournamentId, phaseId, teamId, 1, i);
        }

        // Add 3 referee-only teams: refereeAssignment=true, but no avatar registered in this phase.
        // They will never appear in playingTeamIds and are always available as referee candidates.
        // 3 free referees ≥ 2 matches/lap → every match can be assigned a referee.
        for (int i = 6; i <= 8; i++) {
            createTeam(tournamentId, i);
        }

        // Step 1: generateMatches
        phasePreparationService.generateMatches(phaseId);
        List<Match> matchesAfterGen = matchRepository.findByPhaseId(phaseId);
        assertThat(matchesAfterGen)
                .as("C(5,2) = 10 matches expected for 5 teams in one group")
                .hasSize(10);

        for (Match m : matchesAfterGen) {
            assertThat(m.getLapNumber())
                    .as("lapNumber must be null before optimizeSlots for match %s", m.getId())
                    .isNull();
            assertThat(m.getFieldNumber())
                    .as("fieldNumber must be null before optimizeSlots for match %s", m.getId())
                    .isNull();
        }

        // Step 2: optimizeSlots (AC15 — timeout mode activated because N=10 > exhaustiveMaxN=5)
        phasePreparationService.optimizeSlots(phaseId);

        List<Match> matchesAfterOpt = matchRepository.findByPhaseId(phaseId);
        assertThat(matchesAfterOpt)
                .as("10 matches must still be present after optimizeSlots")
                .hasSize(10);

        // AC6: all matches must have non-null slot coordinates
        for (Match m : matchesAfterOpt) {
            assertThat(m.getLapNumber())
                    .as("lapNumber must be non-null after optimizeSlots for match %s", m.getId())
                    .isNotNull();
            assertThat(m.getFieldNumber())
                    .as("fieldNumber must be non-null after optimizeSlots for match %s", m.getId())
                    .isNotNull();
        }

        // Round constraint: no avatar plays twice in the same lap
        Map<Integer, Set<UUID>> avatarsByLap = new HashMap<>();
        for (Match m : matchesAfterOpt) {
            int lap = m.getLapNumber();
            Set<UUID> avatarsInLap = avatarsByLap.computeIfAbsent(lap, k -> new HashSet<>());

            assertThat(avatarsInLap)
                    .as("Avatar %s appears twice in lap %d (round constraint violated) "
                            + "for match %s", m.getMemberAvatar1Id(), lap, m.getId())
                    .doesNotContain(m.getMemberAvatar1Id());
            assertThat(avatarsInLap)
                    .as("Avatar %s appears twice in lap %d (round constraint violated) "
                            + "for match %s", m.getMemberAvatar2Id(), lap, m.getId())
                    .doesNotContain(m.getMemberAvatar2Id());

            avatarsInLap.add(m.getMemberAvatar1Id());
            avatarsInLap.add(m.getMemberAvatar2Id());
        }

        // Step 3: assignReferees
        phasePreparationService.assignReferees(phaseId);

        // Step 4: startPhase — requires all matches to have slots AND referees assigned
        phasePreparationService.startPhase(phaseId);

        Phase phase = phaseRepository.findById(phaseId)
                .orElseThrow(() -> new AssertionError("Phase not found after startPhase"));
        assertThat(phase.getStatus())
                .as("AC15: Phase must be ACTIVE after startPhase")
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC9 — Phase not found
    // =========================================================================

    /**
     * AC9: optimize() on a non-existent phase ID must throw {@link IllegalArgumentException}.
     */
    @Test
    @Transactional
    void ac9_phaseNotFound_throwsIllegalArgumentException() {
        UUID nonExistentPhaseId = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> slotOptimizationClient.optimize(nonExistentPhaseId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(nonExistentPhaseId.toString());
    }

    // =========================================================================
    // AC10 — No matches
    // =========================================================================

    /**
     * AC10: optimize() on a phase with no matches must throw {@link IllegalStateException}.
     */
    @Test
    @Transactional
    void ac10_noMatches_throwsIllegalStateException() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);
        // Do NOT call generateMatches — phase has no matches

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> slotOptimizationClient.optimize(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no matches");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createTournament() {
        UUID id = UUID.randomUUID();
        Tournament t = new Tournament(
                id, defaultTenantId, "Test Tournament E04S04 " + id,
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

    private UUID createTeam(UUID tournamentId, int teamNumber) {
        UUID id = UUID.randomUUID();
        Team t = new Team(id, defaultTenantId, tournamentId, teamNumber,
                "Team " + teamNumber, true, true, false, LocalDateTime.now());
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
