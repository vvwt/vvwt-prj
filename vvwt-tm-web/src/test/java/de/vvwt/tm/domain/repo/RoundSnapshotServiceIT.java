package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.CascadeRecomputeService;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.RoundSnapshot;
import de.vvwt.tm.domain.SetResultInput;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.snapshot.RoundSnapshotService;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for {@link RoundSnapshotService} — verifies the round-end snapshot
 * mechanism against a real H2 in-memory database with all Flyway migrations applied (E03S13).
 *
 * <p>Tests cover:
 * <ul>
 *   <li>AC7 — event-driven flow: snapshots created on lap advance</li>
 *   <li>AC8 — no snapshot on non-advance (partial lap)</li>
 *   <li>AC6 — regenerate: delete + re-create snapshot</li>
 *   <li>AC13 — tenant scoping: snapshot belongs to active tenant, invisible to other tenants</li>
 *   <li>AC5 — duplicate guard: regenerate replaces, does not duplicate</li>
 * </ul>
 *
 * <p><strong>All tests are non-{@code @Transactional}</strong> because
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} only fires on committed transactions.
 * A test-managed transaction never commits, preventing the snapshot listener from running.
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e03s13db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class RoundSnapshotServiceIT {

    @Autowired private CascadeRecomputeService cascadeService;
    @Autowired private RoundSnapshotService roundSnapshotService;
    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private RoundSnapshotRepository roundSnapshotRepository;

    private UUID defaultTenantId;

    @BeforeEach
    void setUp() {
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        tenantContext.set(defaultTenantId);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // =========================================================================
    // AC7 — Event-driven flow: snapshot created when lap advances
    // =========================================================================

    /**
     * AC7: Full event-driven flow — 2 laps, 2 matches each.
     * <ul>
     *   <li>Complete all matches in lap 0 → phase.currentLapNumber advances to 1 →
     *       snapshot for lap=0 is written</li>
     *   <li>Complete all matches in lap 1 → phase.currentLapNumber advances to 2 →
     *       snapshot for lap=1 is written</li>
     *   <li>Lap=0 snapshot remains unchanged after lap=1 completes (immutability)</li>
     * </ul>
     */
    @Test
    void ac7_eventDrivenFlow_twoLapsProduceTwoSnapshots() {
        TwoLapFixture f = createTwoLapFixture();

        // Finish both matches in lap 0 (BEST_OF_1 deciding set target=15 for standardVolleyball)
        cascadeService.registerMatchResult(new SetResultInput(f.match0aId, 0, 15, 10, null, null));
        cascadeService.registerMatchResult(new SetResultInput(f.match0bId, 0, 10, 15, null, null));

        // Phase must have advanced to lap 1
        Phase phaseAfterLap0 = phaseRepository.findById(f.phaseId).orElseThrow();
        assertThat(phaseAfterLap0.getCurrentLapNumber()).isEqualTo(1)
                .as("AC7: phase.currentLapNumber must advance to 1 after completing lap 0");

        // Snapshot for lap=0 must exist with correct structure
        Optional<RoundSnapshot> snap0 = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 0);
        assertThat(snap0).isPresent()
                .as("AC7: snapshot for lap=0 must be written after lap advances");
        assertThat(snap0.get().getSnapshotPayload())
                .contains("completed_lap_number")
                .contains("team_standings")
                .contains("matches_in_lap")
                .as("AC7: payload must contain required top-level fields");
        assertThat(snap0.get().getTenantId()).isEqualTo(defaultTenantId)
                .as("AC7: snapshot must belong to active tenant");

        UUID snap0Id = snap0.get().getId(); // for immutability check

        // Finish both matches in lap 1
        cascadeService.registerMatchResult(new SetResultInput(f.match1aId, 0, 15, 12, null, null));
        cascadeService.registerMatchResult(new SetResultInput(f.match1bId, 0, 12, 15, null, null));

        // Snapshot for lap=1 must exist
        Optional<RoundSnapshot> snap1 = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 1);
        assertThat(snap1).isPresent()
                .as("AC7: snapshot for lap=1 must be written after second lap advances");

        // AC7: lap=0 snapshot must be unchanged (immutable)
        Optional<RoundSnapshot> snap0Again = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 0);
        assertThat(snap0Again).isPresent();
        assertThat(snap0Again.get().getId()).isEqualTo(snap0Id)
                .as("AC7: lap=0 snapshot must remain unchanged (immutable) after lap=1 completes");
    }

    // =========================================================================
    // AC8 — No snapshot written when only 1 of 2 matches finishes
    // =========================================================================

    @Test
    void ac8_noSnapshotOnNonAdvance_partialLap() {
        TwoLapFixture f = createTwoLapFixture();

        // Only finish match0a — match0b remains open
        cascadeService.registerMatchResult(new SetResultInput(f.match0aId, 0, 15, 10, null, null));

        // Lap must NOT have advanced
        Phase phase = phaseRepository.findById(f.phaseId).orElseThrow();
        assertThat(phase.getCurrentLapNumber()).isZero()
                .as("AC8: lap must not advance when only 1 of 2 matches is terminal");

        // No snapshot for lap=0
        Optional<RoundSnapshot> snap0 = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 0);
        assertThat(snap0).isEmpty()
                .as("AC8: no snapshot must be written when lap did not advance");
    }

    // =========================================================================
    // AC6 — regenerate: delete snapshot, call regenerate, verify re-created
    // =========================================================================

    @Test
    void ac6_regenerate_recreatesDeletedSnapshot() {
        TwoLapFixture f = createTwoLapFixture();

        // Trigger event-driven snapshot for lap=0
        cascadeService.registerMatchResult(new SetResultInput(f.match0aId, 0, 15, 10, null, null));
        cascadeService.registerMatchResult(new SetResultInput(f.match0bId, 0, 10, 15, null, null));

        Optional<RoundSnapshot> original = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 0);
        assertThat(original).isPresent()
                .as("AC6 prerequisite: snapshot for lap=0 must exist");
        UUID originalId = original.get().getId();

        // Delete the snapshot
        roundSnapshotRepository.deleteById(originalId);
        assertThat(roundSnapshotRepository.findByTournamentPhaseAndLap(
                f.tournamentId, f.phaseId, 0)).isEmpty()
                .as("AC6: snapshot must be absent after delete");

        // Call regenerate — must not throw
        assertThatNoException().isThrownBy(() ->
                roundSnapshotService.regenerate(f.tournamentId, f.phaseId, 0));

        // Verify regenerated snapshot exists with correct structure
        Optional<RoundSnapshot> regenerated = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 0);
        assertThat(regenerated).isPresent()
                .as("AC6: snapshot must exist after regenerate");
        assertThat(regenerated.get().getId()).isNotEqualTo(originalId)
                .as("AC6: regenerated snapshot gets a new UUID");
        assertThat(regenerated.get().getSnapshotPayload())
                .contains("completed_lap_number")
                .contains("team_standings")
                .contains("matches_in_lap")
                .as("AC6: regenerated payload must have the same structure as original");
    }

    // =========================================================================
    // AC13 — Tenant scoping enforced
    // =========================================================================

    @Test
    void ac13_tenantScopingEnforced() {
        TwoLapFixture f = createTwoLapFixture();

        // Complete lap 0 to create snapshot
        cascadeService.registerMatchResult(new SetResultInput(f.match0aId, 0, 15, 10, null, null));
        cascadeService.registerMatchResult(new SetResultInput(f.match0bId, 0, 10, 15, null, null));

        // Snapshot must belong to defaultTenantId
        Optional<RoundSnapshot> snap = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 0);
        assertThat(snap).isPresent();
        assertThat(snap.get().getTenantId()).isEqualTo(defaultTenantId)
                .as("AC13: snapshot tenant_id must equal the active TenantContext");

        // Switch to a different tenant — snapshot must be invisible
        UUID otherTenant = UUID.randomUUID();
        tenantContext.set(otherTenant);
        Optional<RoundSnapshot> snapOtherTenant = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 0);
        assertThat(snapOtherTenant).isEmpty()
                .as("AC13: snapshot must not be visible under a different tenant");

        // Restore for tearDown
        tenantContext.set(defaultTenantId);
    }

    // =========================================================================
    // AC5 — Duplicate guard: regenerate replaces, not duplicates
    // =========================================================================

    @Test
    void ac5_duplicateGuard_regenerateReplaces() {
        TwoLapFixture f = createTwoLapFixture();

        // Create snapshot via event
        cascadeService.registerMatchResult(new SetResultInput(f.match0aId, 0, 15, 10, null, null));
        cascadeService.registerMatchResult(new SetResultInput(f.match0bId, 0, 10, 15, null, null));

        Optional<RoundSnapshot> snap1 = roundSnapshotRepository
                .findByTournamentPhaseAndLap(f.tournamentId, f.phaseId, 0);
        assertThat(snap1).isPresent();

        long countBefore = roundSnapshotRepository.count();

        // regenerate replaces the existing row (delete+insert) — net count unchanged
        roundSnapshotService.regenerate(f.tournamentId, f.phaseId, 0);

        long countAfter = roundSnapshotRepository.count();
        assertThat(countAfter).isEqualTo(countBefore)
                .as("AC5: regenerate must replace, not add, the snapshot row");
    }

    // =========================================================================
    // Test fixture
    // =========================================================================

    /**
     * 4 teams, 4 avatars, 4 matches in 2 laps (2 matches per lap).
     * BEST_OF_1 + standardVolleyball (deciding set target = 15).
     * DRAFT tournament status to avoid the active-tournament-per-tenant constraint.
     */
    private TwoLapFixture createTwoLapFixture() {
        UUID tournamentId = UUID.randomUUID();
        tournamentRepository.save(new Tournament(
                tournamentId, defaultTenantId, "Snapshot Test " + tournamentId,
                MatchFormat.BEST_OF_1.name(), "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now()));

        UUID phaseId = UUID.randomUUID();
        phaseRepository.save(new Phase(phaseId, defaultTenantId, tournamentId, 1, "Vorrunde",
                "ACTIVE", 0, LocalDateTime.now()));

        UUID t1 = UUID.randomUUID(); UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID(); UUID t4 = UUID.randomUUID();
        teamRepository.save(new Team(t1, defaultTenantId, tournamentId, 1, "Alpha", true, false, false, LocalDateTime.now()));
        teamRepository.save(new Team(t2, defaultTenantId, tournamentId, 2, "Beta",  true, false, false, LocalDateTime.now()));
        teamRepository.save(new Team(t3, defaultTenantId, tournamentId, 3, "Gamma", true, false, false, LocalDateTime.now()));
        teamRepository.save(new Team(t4, defaultTenantId, tournamentId, 4, "Delta", true, false, false, LocalDateTime.now()));

        UUID av1 = UUID.randomUUID(); UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID(); UUID av4 = UUID.randomUUID();
        teamAvatarRepository.save(new TeamAvatar(av1, defaultTenantId, tournamentId, phaseId, 1, 1, t1, null, LocalDateTime.now()));
        teamAvatarRepository.save(new TeamAvatar(av2, defaultTenantId, tournamentId, phaseId, 1, 2, t2, null, LocalDateTime.now()));
        teamAvatarRepository.save(new TeamAvatar(av3, defaultTenantId, tournamentId, phaseId, 1, 3, t3, null, LocalDateTime.now()));
        teamAvatarRepository.save(new TeamAvatar(av4, defaultTenantId, tournamentId, phaseId, 1, 4, t4, null, LocalDateTime.now()));

        UUID m0a = UUID.randomUUID(); UUID m0b = UUID.randomUUID();
        UUID m1a = UUID.randomUUID(); UUID m1b = UUID.randomUUID();
        // lap 0
        matchRepository.save(new Match(m0a, defaultTenantId, tournamentId, phaseId,
                av1, av2, MatchState.OPEN.getLegacyCode(), MatchFormat.BEST_OF_1.getMaxSets(),
                0, 1, null, null, null, LocalDateTime.now()));
        matchRepository.save(new Match(m0b, defaultTenantId, tournamentId, phaseId,
                av3, av4, MatchState.OPEN.getLegacyCode(), MatchFormat.BEST_OF_1.getMaxSets(),
                0, 2, null, null, null, LocalDateTime.now()));
        // lap 1
        matchRepository.save(new Match(m1a, defaultTenantId, tournamentId, phaseId,
                av1, av3, MatchState.OPEN.getLegacyCode(), MatchFormat.BEST_OF_1.getMaxSets(),
                1, 1, null, null, null, LocalDateTime.now()));
        matchRepository.save(new Match(m1b, defaultTenantId, tournamentId, phaseId,
                av2, av4, MatchState.OPEN.getLegacyCode(), MatchFormat.BEST_OF_1.getMaxSets(),
                1, 2, null, null, null, LocalDateTime.now()));

        return new TwoLapFixture(defaultTenantId, tournamentId, phaseId, m0a, m0b, m1a, m1b);
    }

    private static class TwoLapFixture {
        final UUID tenantId;
        final UUID tournamentId;
        final UUID phaseId;
        final UUID match0aId;
        final UUID match0bId;
        final UUID match1aId;
        final UUID match1bId;

        TwoLapFixture(UUID tenantId, UUID tournamentId, UUID phaseId,
                      UUID match0aId, UUID match0bId, UUID match1aId, UUID match1bId) {
            this.tenantId = tenantId;
            this.tournamentId = tournamentId;
            this.phaseId = phaseId;
            this.match0aId = match0aId;
            this.match0bId = match0bId;
            this.match1aId = match1aId;
            this.match1bId = match1bId;
        }
    }
}
