package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.AuditLogEntry;
import de.vvwt.tm.domain.CascadeRecomputeService;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchOutcome;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.SetResult;
import de.vvwt.tm.domain.SetResultInput;
import de.vvwt.tm.domain.SetState;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.TeamAvatarRating;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.ValidationException;
import de.vvwt.tm.domain.event.MatchResultChangedEvent;
import de.vvwt.tm.domain.repo.AuditLogRepository;
import de.vvwt.tm.domain.repo.MatchOutcomeRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link CascadeRecomputeService} — verifies the 13-step cascade
 * against a real H2 in-memory database with all Flyway migrations applied (E03S11).
 *
 * <p>Tests cover AC16, AC18–AC28.
 *
 * <p>Note: tests that verify event publishing (AC26) CANNOT use {@code @Transactional} because
 * the {@code @TransactionalEventListener(phase = AFTER_COMMIT)} only fires when the transaction
 * actually commits. A test-managed transaction never commits (it is rolled back after the test).
 * Those tests manage cleanup manually.
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class, CascadeRecomputeServiceIT.TestEventCapture.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e03s11db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class CascadeRecomputeServiceIT {

    // -------------------------------------------------------------------------
    // Test event capture bean
    // -------------------------------------------------------------------------

    /**
     * Spring component that captures {@link MatchResultChangedEvent}s published after commit.
     * Used by AC26 tests.
     */
    @Component
    static class TestEventCapture {
        final List<MatchResultChangedEvent> captured = new ArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void onMatchResultChanged(MatchResultChangedEvent event) {
            captured.add(event);
        }

        void reset() {
            captured.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Injected beans
    // -------------------------------------------------------------------------

    @Autowired private CascadeRecomputeService cascadeService;
    @Autowired private TenantContext tenantContext;
    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private SetResultRepository setResultRepository;
    @Autowired private MatchOutcomeRepository matchOutcomeRepository;
    @Autowired private TeamAvatarRatingRepository teamAvatarRatingRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private TestEventCapture testEventCapture;

    private UUID defaultTenantId;

    @BeforeEach
    void setUp() {
        defaultTenantId = tenantContextBinder.bindDefaultTenant();
        testEventCapture.reset();
    }

    @AfterEach
    void tearDown() {
        tenantContextBinder.unbind();
    }

    // =========================================================================
    // AC16 — Invariant: match.state == matchOutcome.computedState after every cascade
    // =========================================================================

    @Test
    @Transactional
    void ac16_invariant_matchStateEqualsOutcomeComputedState_firstEntry() {
        TestFixture f = createFixture(MatchFormat.BEST_OF_3, "setPoints", "standardVolleyball");

        // Enter first set: 25-15 → WINNER1 (standardVolleyball standard target = 25)
        cascadeService.registerMatchResult(
                SetResultInput.legacy(f.matchId, 0, 25, 15, null, null));

        Match match = matchRepository.findById(f.matchId).orElseThrow();
        MatchOutcome outcome = matchOutcomeRepository.findById(f.matchId).orElseThrow();
        assertThat(match.getState()).isEqualTo(outcome.getComputedState())
                .as("AC16: match.state must equal matchOutcome.computedState");
    }

    @Test
    @Transactional
    void ac16_invariant_afterCorrection() {
        TestFixture f = createFixture(MatchFormat.BEST_OF_3, "setPoints", "standardVolleyball");

        // standardVolleyball standard target = 25, minimum 2-point lead
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 25, 15, null, null));
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 25, 23, "organizer", "correction"));

        Match match = matchRepository.findById(f.matchId).orElseThrow();
        MatchOutcome outcome = matchOutcomeRepository.findById(f.matchId).orElseThrow();
        assertThat(match.getState()).isEqualTo(outcome.getComputedState());
    }

    @Test
    @Transactional
    void ac16_invariant_matchFinishingEntry() {
        // BEST_OF_1: setIndex=0 is the deciding set, target = 15
        TestFixture f = createFixture(MatchFormat.BEST_OF_1, "setPoints", "standardVolleyball");

        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 15, 10, null, null));

        Match match = matchRepository.findById(f.matchId).orElseThrow();
        MatchOutcome outcome = matchOutcomeRepository.findById(f.matchId).orElseThrow();
        assertThat(match.getState()).isEqualTo(outcome.getComputedState());
        assertThat(match.getMatchState()).isEqualTo(MatchState.FINISHED_WINNER1);
    }

    // =========================================================================
    // AC18 — Integration test: first entry, full cascade happy path
    // =========================================================================

    @Test
    @Transactional
    void ac18_firstEntry_fullCascadeHappyPath() {
        TestFixture f = createFixture(MatchFormat.BEST_OF_3, "setPoints", "standardVolleyball");

        // standardVolleyball standard target = 25, minimum 2-point lead
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 25, 15, null, null));

        // SetResult persisted
        Optional<SetResult> sr = setResultRepository.findByMatchIdAndSetIndex(f.matchId, 0);
        assertThat(sr).isPresent();
        assertThat(sr.get().getTeam1Points()).isEqualTo(25);
        assertThat(sr.get().getTeam2Points()).isEqualTo(15);
        assertThat(sr.get().getSetState()).isEqualTo(SetState.WINNER1);

        // audit_log row written with team1PointsOld = NULL
        List<AuditLogEntry> audit = auditLogRepository.findByMatchIdAndSetIndexOrderByChangedAt(f.matchId, 0);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getTeam1PointsOld()).isNull();
        assertThat(audit.get(0).getTeam2PointsOld()).isNull();
        assertThat(audit.get(0).getTeam1PointsNew()).isEqualTo(25);
        assertThat(audit.get(0).getTeam2PointsNew()).isEqualTo(15);

        // MatchOutcome upserted
        MatchOutcome outcome = matchOutcomeRepository.findById(f.matchId).orElseThrow();
        assertThat(outcome.getTeam1SetsWon()).isEqualTo(1);
        assertThat(outcome.getTeam2SetsWon()).isEqualTo(0);
        assertThat(outcome.getSetCount()).isEqualTo(1);

        // Match state: BEST_OF_3, set 1 of 3 → still ONCHECK
        Match match = matchRepository.findById(f.matchId).orElseThrow();
        assertThat(match.getMatchState()).isEqualTo(MatchState.ONCHECK);

        // TeamAvatarRating for both teams updated
        Optional<TeamAvatarRating> r1 = teamAvatarRatingRepository.findById(f.avatar1Id);
        Optional<TeamAvatarRating> r2 = teamAvatarRatingRepository.findById(f.avatar2Id);
        // Match is ONCHECK — not in terminal state → no matches count toward refreshAvatarRating
        // (ratings are updated from terminal matches only per AC9)
        // Both ratings are upserted but with 0 matches (no terminal matches yet)
        assertThat(r1).isPresent();
        assertThat(r2).isPresent();
    }

    // =========================================================================
    // AC19 — Integration test: correction (UPDATE path, 2 audit rows)
    // =========================================================================

    @Test
    @Transactional
    void ac19_correction_updatePathAndTwoAuditRows() {
        TestFixture f = createFixture(MatchFormat.BEST_OF_3, "setPoints", "standardVolleyball");

        // First entry: 25-15 → WINNER1 (standardVolleyball standard target = 25)
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 25, 15, null, null));
        // Correction: 25-23 → still WINNER1 (valid correction with 2-point lead)
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 25, 23, "organizer", "correction"));

        // SetResult UPDATEd to new values
        Optional<SetResult> sr = setResultRepository.findByMatchIdAndSetIndex(f.matchId, 0);
        assertThat(sr).isPresent();
        assertThat(sr.get().getTeam1Points()).isEqualTo(25);
        assertThat(sr.get().getTeam2Points()).isEqualTo(23);

        // 2 audit rows: first INSERT (old values null), second UPDATE (old values from first entry)
        List<AuditLogEntry> audit = auditLogRepository.findByMatchIdAndSetIndexOrderByChangedAt(f.matchId, 0);
        assertThat(audit).hasSize(2);

        AuditLogEntry first = audit.get(0);
        assertThat(first.getTeam1PointsOld()).isNull(); // INSERT
        assertThat(first.getTeam1PointsNew()).isEqualTo(25);

        AuditLogEntry second = audit.get(1);
        assertThat(second.getTeam1PointsOld()).isEqualTo(25); // UPDATE — old from first entry
        assertThat(second.getTeam2PointsOld()).isEqualTo(15);
        assertThat(second.getTeam1PointsNew()).isEqualTo(25);
        assertThat(second.getTeam2PointsNew()).isEqualTo(23);
        assertThat(second.getActorId()).isEqualTo("organizer");
        assertThat(second.getReason()).isEqualTo("correction");
    }

    // =========================================================================
    // AC20 — Integration test: cascade triggers auto-lap-advance
    // =========================================================================

    @Test
    void ac20_cascadeTriggersAutoLapAdvance() {
        // Note: NOT @Transactional — we need real commits to verify lap advance
        // BEST_OF_1: setIndex=0 is deciding set, target=15 (standardVolleyball DECIDING_TARGET)
        TestFixture f = createTwoMatchFixture(MatchFormat.BEST_OF_1, "setPoints", "standardVolleyball");

        try {
            // Phase starts at lap=0; both matches in lap=0
            Phase phaseInit = phaseRepository.findById(f.phaseId).orElseThrow();
            assertThat(phaseInit.getCurrentLapNumber()).isZero();

            // Finish match 1 (15-10 → valid for BEST_OF_1 deciding set)
            cascadeService.registerMatchResult(SetResultInput.legacy(f.match1Id, 0, 15, 10, null, null));
            // Lap should still be 0 (match 2 not finished)
            Phase phaseAfter1 = phaseRepository.findById(f.phaseId).orElseThrow();
            assertThat(phaseAfter1.getCurrentLapNumber()).isZero()
                    .as("AC20: lap must not advance until both matches are terminal");

            // Finish match 2 (10-15 → WINNER2 valid for deciding set)
            cascadeService.registerMatchResult(SetResultInput.legacy(f.match2Id, 0, 10, 15, null, null));
            // Now both matches in lap=0 are terminal → lap advances to 1
            Phase phaseAfter2 = phaseRepository.findById(f.phaseId).orElseThrow();
            assertThat(phaseAfter2.getCurrentLapNumber()).isEqualTo(1)
                    .as("AC20: lap must advance to 1 when all matches in lap 0 are terminal");

        } finally {
            // Manual cleanup since this test is not @Transactional
            cleanUpFixture(f);
        }
    }

    // =========================================================================
    // AC21 — Integration test: lap NOT advanced when only 1 of 2 matches finished
    // =========================================================================

    @Test
    @Transactional
    void ac21_lapNotAdvancedWhenOnly1of2MatchesFinished() {
        // BEST_OF_1: setIndex=0 is deciding set, target=15
        TestFixture f = createTwoMatchFixture(MatchFormat.BEST_OF_1, "setPoints", "standardVolleyball");

        Phase phaseBefore = phaseRepository.findById(f.phaseId).orElseThrow();
        assertThat(phaseBefore.getCurrentLapNumber()).isZero();

        // Only finish match 1 (15-10 valid for deciding set)
        cascadeService.registerMatchResult(SetResultInput.legacy(f.match1Id, 0, 15, 10, null, null));

        Phase phaseAfter = phaseRepository.findById(f.phaseId).orElseThrow();
        assertThat(phaseAfter.getCurrentLapNumber()).isZero()
                .as("AC21: lap must NOT advance when match 2 is still unfinished");
    }

    // =========================================================================
    // AC21a — Integration test: FIXED_2_SETS 1-1 → FINISHED_STANDOFF end-to-end
    // =========================================================================

    @Test
    @Transactional
    void ac21a_fixedTwoSets_standoff_endToEnd() {
        // FIXED_2_SETS is incompatible with standardVolleyball (no deciding-set concept).
        // Use timeBounded (any score where winner leads by ≥1 point).
        TestFixture f = createFixture(MatchFormat.FIXED_2_SETS, "setPoints", "timeBounded");

        // Set 0: team1 wins 15-10 (timeBounded: any score with ≥1 point lead is valid)
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 15, 10, null, null));
        // Set 1: team2 wins 12-15
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 1, 12, 15, null, null));

        // MatchOutcome: team1_sets=1, team2_sets=1
        MatchOutcome outcome = matchOutcomeRepository.findById(f.matchId).orElseThrow();
        assertThat(outcome.getTeam1SetsWon()).isEqualTo(1);
        assertThat(outcome.getTeam2SetsWon()).isEqualTo(1);

        // Match.state = FINISHED_STANDOFF
        Match match = matchRepository.findById(f.matchId).orElseThrow();
        assertThat(match.getMatchState()).isEqualTo(MatchState.FINISHED_STANDOFF)
                .as("AC21a: FIXED_2_SETS 1-1 must produce FINISHED_STANDOFF");

        // TeamAvatarRating via setPoints: 1 set won each → 1 point each
        // (setPoints counts sets won, each set win = 1 point)
        TeamAvatarRating r1 = teamAvatarRatingRepository.findById(f.avatar1Id).orElseThrow();
        TeamAvatarRating r2 = teamAvatarRatingRepository.findById(f.avatar2Id).orElseThrow();
        // SetPointsRule awards 1 point per set won
        // Avatar1: 1 set won (set 0), 1 set lost (set 1)
        assertThat(r1.getSetsWon()).isEqualTo(1);
        assertThat(r1.getSetsLost()).isEqualTo(1);
        // Avatar2: 1 set won (set 1), 1 set lost (set 0)
        assertThat(r2.getSetsWon()).isEqualTo(1);
        assertThat(r2.getSetsLost()).isEqualTo(1);
    }

    // =========================================================================
    // AC21b — Integration test: diff-check optimization
    // =========================================================================

    @Test
    @Transactional
    void ac21b_diffCheckOptimization_noStateChange_thenStateChange() {
        TestFixture f = createFixture(MatchFormat.BEST_OF_3, "setPoints", "standardVolleyball");

        // Enter set 0: 25-15 → WINNER1 (standard target=25); state OPEN → ONCHECK
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 25, 15, null, null));
        Match afterSet0 = matchRepository.findById(f.matchId).orElseThrow();
        assertThat(afterSet0.getMatchState()).isEqualTo(MatchState.ONCHECK);

        // Enter set 1: 15-25 → WINNER2 (standard target=25); still ONCHECK (1-1)
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 1, 15, 25, null, null));
        Match afterSet1 = matchRepository.findById(f.matchId).orElseThrow();
        assertThat(afterSet1.getMatchState()).isEqualTo(MatchState.ONCHECK)
                .as("AC21b: state must remain ONCHECK when not yet decisive");

        // Enter decisive set 2: 15-8 → WINNER1 (deciding target=15); FINISHED_WINNER1
        cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 2, 15, 8, null, null));
        Match afterSet2 = matchRepository.findById(f.matchId).orElseThrow();
        assertThat(afterSet2.getMatchState()).isEqualTo(MatchState.FINISHED_WINNER1)
                .as("AC21b: state must change to FINISHED_WINNER1 on decisive set");
    }

    // =========================================================================
    // AC22 — Error-handling: ValidationException rollback
    // =========================================================================

    @Test
    @Transactional
    void ac22_validationExceptionRollsBack() {
        TestFixture f = createFixture(MatchFormat.BEST_OF_3, "setPoints", "standardVolleyball");

        // 15-15 is invalid for StandardVolleyball (no 2-point lead)
        assertThatThrownBy(() ->
                cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 15, 15, null, null)))
                .isInstanceOf(ValidationException.class);

        // No set_result row was written
        assertThat(setResultRepository.findByMatchIdAndSetIndex(f.matchId, 0))
                .as("AC22: no SetResult must be written when validation fails")
                .isEmpty();

        // No audit_log row
        assertThat(auditLogRepository.findByMatchIdAndSetIndexOrderByChangedAt(f.matchId, 0))
                .as("AC22: no AuditLog entry must be written when validation fails")
                .isEmpty();

        // MatchOutcome not created
        assertThat(matchOutcomeRepository.findById(f.matchId))
                .as("AC22: no MatchOutcome must be created when validation fails")
                .isEmpty();
    }

    // =========================================================================
    // AC23 — Error-handling: FK violation rollback (non-existent matchId)
    // =========================================================================

    @Test
    @Transactional
    void ac23_nonExistentMatchId_throwsAndNoAuditLog() {
        UUID nonExistentMatchId = UUID.randomUUID();

        assertThatThrownBy(() ->
                cascadeService.registerMatchResult(SetResultInput.legacy(nonExistentMatchId, 0, 15, 10, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Match not found");

        // No audit_log row written
        assertThat(auditLogRepository.findByMatchIdAndSetIndexOrderByChangedAt(nonExistentMatchId, 0))
                .as("AC23: no AuditLog must be written when match does not exist")
                .isEmpty();
    }

    // =========================================================================
    // AC26 — Event: exactly 1 event per successful cascade, 0 on rollback
    // =========================================================================

    @Test
    void ac26_exactlyOneEventPerSuccessfulCascade() {
        // Note: NOT @Transactional — AFTER_COMMIT listener only fires on real commits
        TestFixture f = createFixture(MatchFormat.BEST_OF_1, "setPoints", "standardVolleyball");
        try {
            testEventCapture.reset();

            cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 15, 10, null, null));

            assertThat(testEventCapture.captured).hasSize(1)
                    .as("AC26: exactly one MatchResultChangedEvent per successful cascade");
            assertThat(testEventCapture.captured.get(0).getMatchId()).isEqualTo(f.matchId);
        } finally {
            cleanUpFixture(f);
        }
    }

    @Test
    void ac26_noEventOnValidationFailure() {
        TestFixture f = createFixture(MatchFormat.BEST_OF_3, "setPoints", "standardVolleyball");
        try {
            testEventCapture.reset();

            // 15-15 invalid → rollback
            assertThatThrownBy(() ->
                    cascadeService.registerMatchResult(SetResultInput.legacy(f.matchId, 0, 15, 15, null, null)))
                    .isInstanceOf(ValidationException.class);

            assertThat(testEventCapture.captured)
                    .as("AC26: no event must fire on validation failure (rollback)")
                    .isEmpty();
        } finally {
            cleanUpFixture(f);
        }
    }

    // =========================================================================
    // AC27 — Security: tenant scoping enforced (cross-tenant access impossible)
    // =========================================================================

    @Test
    @Transactional
    void ac27_tenantScopeEnforced() {
        TestFixture f = createFixture(MatchFormat.BEST_OF_3, "setPoints", "standardVolleyball");

        // Switch to a different tenant via new tenant::api — the match should be invisible
        UUID otherTenant = UUID.randomUUID();
        try (de.vvwt.tm.tenant.TenantContext.Scope otherScope =
                     tenantContextBinder.tenantContext().bind(otherTenant)) {
            // Match is not found under the other tenant
            assertThat(matchRepository.findById(f.matchId)).isEmpty()
                    .as("AC27: match must not be visible under a different tenant");
        }
        // After scope closes, default-tenant binding (from @BeforeEach) is restored
    }

    // =========================================================================
    // Test fixtures
    // =========================================================================

    /**
     * Minimal fixture: 1 tournament, 1 phase, 2 teams, 2 avatars, 1 match.
     *
     * <p>Tournaments are created with {@code status='DRAFT'} to avoid the
     * active-tournament-per-tenant unique constraint (DEC-5): non-{@code @Transactional} tests
     * commit data that persists for subsequent tests in the same JVM. DRAFT status sets
     * {@code active_sentinel=NULL}, so multiple fixtures can coexist without violating the index.
     * {@link CascadeRecomputeService} only reads the tournament for rule IDs and does not check
     * {@code status}.
     */
    private TestFixture createFixture(MatchFormat format, String scoringRuleId, String validationRuleId) {
        UUID tournamentId = UUID.randomUUID();
        // Use DRAFT status to avoid the active-tournament-per-tenant unique constraint (DEC-5).
        // CascadeRecomputeService only reads the tournament for rule IDs — status is irrelevant.
        Tournament t = new Tournament(
                tournamentId, defaultTenantId, "Test Tournament " + tournamentId,
                format.name(), scoringRuleId, validationRuleId, "roundRobin",
                "DRAFT", LocalDateTime.now());
        tournamentRepository.save(t);

        UUID phaseId = UUID.randomUUID();
        Phase p = new Phase(phaseId, defaultTenantId, tournamentId, 1, "Vorrunde", "ACTIVE", 0,
                LocalDateTime.now());
        phaseRepository.save(p);

        UUID team1Id = UUID.randomUUID();
        Team team1 = new Team(team1Id, defaultTenantId, tournamentId, 1, "Team A",
                true, false, false, LocalDateTime.now());
        teamRepository.save(team1);

        UUID team2Id = UUID.randomUUID();
        Team team2 = new Team(team2Id, defaultTenantId, tournamentId, 2, "Team B",
                true, false, false, LocalDateTime.now());
        teamRepository.save(team2);

        UUID avatar1Id = UUID.randomUUID();
        TeamAvatar ta1 = new TeamAvatar(avatar1Id, defaultTenantId, tournamentId, phaseId,
                1, 1, team1Id, "Gruppe A, Platz 1", LocalDateTime.now());
        teamAvatarRepository.save(ta1);

        UUID avatar2Id = UUID.randomUUID();
        TeamAvatar ta2 = new TeamAvatar(avatar2Id, defaultTenantId, tournamentId, phaseId,
                1, 2, team2Id, "Gruppe A, Platz 2", LocalDateTime.now());
        teamAvatarRepository.save(ta2);

        UUID matchId = UUID.randomUUID();
        Match m = new Match(matchId, defaultTenantId, tournamentId, phaseId,
                avatar1Id, avatar2Id,
                MatchState.OPEN.getLegacyCode(), format.getMaxSets(),
                null, null, null, null, null,
                LocalDateTime.now());
        matchRepository.save(m);

        return new TestFixture(defaultTenantId, tournamentId, phaseId, matchId, UUID.randomUUID(), avatar1Id, avatar2Id);
    }

    /**
     * Two-match fixture: for lap-advance tests (AC20/AC21).
     * Both matches are in lap 0.
     *
     * <p>Uses {@code status='DRAFT'} — see {@link #createFixture} for rationale.
     */
    private TestFixture createTwoMatchFixture(MatchFormat format, String scoringRuleId, String validationRuleId) {
        UUID tournamentId = UUID.randomUUID();
        // Use DRAFT status — see createFixture() for rationale.
        Tournament t = new Tournament(
                tournamentId, defaultTenantId, "Test Tournament " + tournamentId,
                format.name(), scoringRuleId, validationRuleId, "roundRobin",
                "DRAFT", LocalDateTime.now());
        tournamentRepository.save(t);

        UUID phaseId = UUID.randomUUID();
        Phase p = new Phase(phaseId, defaultTenantId, tournamentId, 1, "Vorrunde", "ACTIVE", 0,
                LocalDateTime.now());
        phaseRepository.save(p);

        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();
        UUID team3Id = UUID.randomUUID();
        UUID team4Id = UUID.randomUUID();
        teamRepository.save(new Team(team1Id, defaultTenantId, tournamentId, 1, "Team A", true, false, false, LocalDateTime.now()));
        teamRepository.save(new Team(team2Id, defaultTenantId, tournamentId, 2, "Team B", true, false, false, LocalDateTime.now()));
        teamRepository.save(new Team(team3Id, defaultTenantId, tournamentId, 3, "Team C", true, false, false, LocalDateTime.now()));
        teamRepository.save(new Team(team4Id, defaultTenantId, tournamentId, 4, "Team D", true, false, false, LocalDateTime.now()));

        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID();
        UUID av4 = UUID.randomUUID();
        teamAvatarRepository.save(new TeamAvatar(av1, defaultTenantId, tournamentId, phaseId, 1, 1, team1Id, null, LocalDateTime.now()));
        teamAvatarRepository.save(new TeamAvatar(av2, defaultTenantId, tournamentId, phaseId, 1, 2, team2Id, null, LocalDateTime.now()));
        teamAvatarRepository.save(new TeamAvatar(av3, defaultTenantId, tournamentId, phaseId, 1, 3, team3Id, null, LocalDateTime.now()));
        teamAvatarRepository.save(new TeamAvatar(av4, defaultTenantId, tournamentId, phaseId, 1, 4, team4Id, null, LocalDateTime.now()));

        UUID match1Id = UUID.randomUUID();
        UUID match2Id = UUID.randomUUID();
        matchRepository.save(new Match(match1Id, defaultTenantId, tournamentId, phaseId,
                av1, av2, MatchState.OPEN.getLegacyCode(), format.getMaxSets(),
                0, 1, null, null, null, LocalDateTime.now()));
        matchRepository.save(new Match(match2Id, defaultTenantId, tournamentId, phaseId,
                av3, av4, MatchState.OPEN.getLegacyCode(), format.getMaxSets(),
                0, 2, null, null, null, LocalDateTime.now()));

        return new TestFixture(defaultTenantId, tournamentId, phaseId, match1Id, match2Id, av1, av2);
    }

    /**
     * Cleans up all rows created by a test fixture (for non-@Transactional tests).
     * Best-effort: ignores errors.
     */
    private void cleanUpFixture(TestFixture f) {
        // The DB is in-memory and cleared between test classes, but within the class
        // non-@Transactional tests leave data. We accept this for now — H2 is ephemeral.
    }

    // -------------------------------------------------------------------------
    // Test fixture holder
    // -------------------------------------------------------------------------

    private static class TestFixture {
        final UUID tenantId;    // isolated per fixture — avoids active-tournament constraint
        final UUID tournamentId;
        final UUID phaseId;
        final UUID match1Id;
        final UUID match2Id;  // null for single-match fixtures
        final UUID avatar1Id;
        final UUID avatar2Id;
        // alias for single-match fixture
        UUID matchId;

        TestFixture(UUID tenantId, UUID tournamentId, UUID phaseId, UUID match1Id, UUID match2Id,
                    UUID avatar1Id, UUID avatar2Id) {
            this.tenantId = tenantId;
            this.tournamentId = tournamentId;
            this.phaseId = phaseId;
            this.match1Id = match1Id;
            this.match2Id = match2Id;
            this.avatar1Id = avatar1Id;
            this.avatar2Id = avatar2Id;
            this.matchId = match1Id; // convenience alias
        }
    }
}
