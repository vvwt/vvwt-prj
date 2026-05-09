package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.api.Assertions;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DAO IT for {@code DefaultDraftService.apply()} — verifies Phase record creation.
 *
 * <p>Originally authored as E48S17 RED-first to verify apply() creates NO TeamAvatars (Phase-1
 * distribution removed). Updated in E51S02 to reflect DEC-55 D-1: apply() now DOES create
 * structural TeamAvatars for all phases. The core Phase-creation assertion (3 PENDING phases)
 * remains unchanged; the avatar-count assertion is updated to the new expected value.
 *
 * <p>E51S08 RED-first: this commit adds the {@link SlotOptConfig} override and the {@link
 * #waitForPipelineQuiescent(UUID)} helper — but does NOT yet call the helper from {@link
 * #tearDown()}. In this state, {@code tearDown()} is still race-prone: the async {@code
 * MatchGenJobListener} may insert {@code match} rows after {@code tearDown()} has already deleted
 * the {@code phase} rows, triggering {@code FK_MATCH_PHASE: FK_MATCH_PHASE} {@code
 * DataIntegrityViolationException}. The GREEN commit (immediately following this one) calls {@code
 * waitForPipelineQuiescent(tournamentId)} as the first line of {@code tearDown()} to eliminate the
 * race. RED evidence: intermittent failure in repeated runs of this IT before the GREEN commit.
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Rule 1: Schema from Flyway migration (Spring manages schema automatically via
 *       {@code @SpringBootTest})
 *   <li>Rule 2: assertj-db as independent persistence verifier (NOT draftService read-path)
 *   <li>Rule 3: Fixture data inserted via direct JDBC (not via draftService)
 * </ul>
 *
 * @see DraftService
 * @see de.vvwt.tm.tournament.internal.DefaultDraftService
 * @see <a href="E48S17">E48S17 — Phase-1-Distribution removal (original RED-first)</a>
 * @see <a href="E51S02">E51S02 — Avatar persistence at apply-time (DEC-55 D-1 update)</a>
 * @see <a href="E51S08">E51S08 — Symmetric waitForPipelineQuiescent quiescence barrier
 *     (RED-first)</a>
 * @see <a href="E51S17">E51S17 — Equilibrium contract alignment (DEC-55 D-3 + DEC-56 D-3)</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-46">DEC-46 — DEC-26 scope extension to all vvwt-prj modules</a>
 * @see <a href="DEC-55">DEC-55 D-1 — Avatar-Erzeugung-Zeitpunkt verschoben auf DraftConfig-Apply;
 *     D-3 step 1 siegerehrung-skip mechanism</a>
 * @see <a href="DEC-56">DEC-56 D-3 — L1+L2 always mandatory; matches reference avatar.id not
 *     teamId</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:applynoteavatarit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({
    TenantContextTestSupport.class,
    DefaultDraftServiceApplyNoTeamAvatarsIT.SlotOptConfig.class
})
@DisplayName("DefaultDraftService apply() — Phase creation + avatar persistence IT — E48S17/E51S02")
class DefaultDraftServiceApplyNoTeamAvatarsIT {

    /**
     * Overrides the production {@code routingSlotOptimizationClient} with a no-op that returns
     * immediately. This prevents async slot-optimization from holding PHASE/TOURNAMENT row-locks
     * during tearDown (H2 FK violation on DELETE FROM tournament while SlotOptInvocationListener TX
     * is still active).
     *
     * <p>E51S08: ported from {@link DefaultDraftServiceApplyAvatarsIT.SlotOptConfig} (added there
     * at {@code e25b917} / E51S04). The NoTeamAvatarsIT was left without this override at {@code
     * e25b917} — the asymmetric omission that this Story repairs.
     */
    @TestConfiguration
    static class SlotOptConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                // No-op: returns immediately so SlotOptInvocationListener completes before tearDown
            };
        }
    }

    /** Subject: inject via public interface per DEC-36. */
    @Autowired private DraftService draftService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    private UUID locationId;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "ApplyNoAvatarIT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "ApplyNoAvatar IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT", // E48S22: apply() requires DRAFT status (AC-IMPL-APPLY-FOUR-OPS-ATOMIC)
                LocalDateTime.now(),
                2,
                6);

        // Insert 3 participating teams (direct JDBC — DEC-26 Rule 3)
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    tournamentId,
                    i,
                    "Team " + i,
                    true,
                    LocalDateTime.now());
        }
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // Wait for async background pipeline (MatchGenJobListener, SlotOptInvocationListener) to
        // quiesce before deleting. Async REQUIRES_NEW transactions may still write to phase/match
        // after tearDown's DELETE — causing FK violations on subsequent deletes (E51S08 fix for
        // FK_MATCH_PHASE race: symmetric port of the quiescence barrier from
        // DefaultDraftServiceApplyAvatarsIT.tearDown, which had it since e25b917 / E51S04).
        waitForPipelineQuiescent(tournamentId);

        // H2 FK-safe deletion with referential-integrity checks temporarily disabled.
        // This avoids residual FK violations from async transactions that committed a phase row
        // after tearDown deleted the same tournament's phases (race window between quiesce-check
        // and delete). SET REFERENTIAL_INTEGRITY FALSE is H2-specific and safe here: the in-memory
        // test DB is torn down at the end of the test suite anyway.
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update(
                    "DELETE FROM phase_breaks WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        tenantBinder.unbind();
    }

    /**
     * Polls until the background pipeline for {@code tournamentId} has fully quiesced.
     *
     * <h2>Quiescence definition (E51S18 — updated from E51S17)</h2>
     *
     * <p>The pipeline is quiescent when {@code COUNT(PREPARED phases) == totalPhases}. Per DEC-59
     * Clause E, the siegerehrung phase now reaches {@code PREPARED} via vacuous L1+L2 execution
     * (per-gameMode {@link de.vvwt.tm.tournament.internal.SiegerehrungMatchGenerator} returns an
     * empty match list; L2 is invoked as a no-op; {@code PhaseLifecycleService.transition(PENDING →
     * PREPARED "match-gen-done")} fires per DEC-55 D-4). ALL phases (including siegerehrung) must
     * eventually reach {@code PREPARED}. Quiescence is declared when {@code totalPhases} are {@code
     * PREPARED}.
     *
     * <h2>E51S17 → E51S18 change</h2>
     *
     * <p>E51S17 used {@code expectedPrepared = totalPhases - 1} because siegerehrung was guaranteed
     * to stay {@code PENDING} (no {@code MatchGenJobScheduledEvent} published for it). After DEC-59
     * Clause E operationalization (E51S18), {@code MatchGenJobScheduledEvent} IS published for all
     * phases including siegerehrung. The siegerehrung-specific no-op-generator returns an empty
     * match list, but the lifecycle transitions the same as for non-siegerehrung phases: {@code
     * PENDING → PREPARED "match-gen-done"}.
     *
     * <h2>Why totalPhases-based rather than last_job_state-based?</h2>
     *
     * <p>{@link de.vvwt.tm.tournament.internal.MatchGenJobExecutor} does NOT set an intermediate
     * {@code 'match_gen_running'} state — phases go directly {@code NULL → 'idle'} atomically with
     * PENDING→PREPARED in a single {@code REQUIRES_NEW} TX (E51S14 wiring). Any approach that
     * compares {@code last_job_state IS NOT NULL} (entered) with {@code last_job_state = 'idle'}
     * races when Phase 1 is {@code 'idle'} but Phase 2's {@code @Async} thread has not yet been
     * scheduled — Phase 2 is still {@code PENDING + NULL}, indistinguishable from a siegerehrung
     * phase by {@code last_job_state} alone. The {@code totalPhases} condition is immune because it
     * checks the FINAL STATE ({@code PREPARED}), not the in-progress state.
     *
     * <p>Times out after 5 seconds total. On budget exhaustion throws {@link AssertionError} with
     * diagnostic phase rows — satisfying AC-ERROR-HANDLING-QUIESCENCE-BUDGET-OBSERVABLE.
     *
     * @param tournamentId the tournament whose background pipeline to wait for
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws AssertionError if the pipeline does not quiesce within 5 seconds
     */
    @SuppressWarnings("java:S2925") // Thread.sleep is intentional here — deterministic poll wait
    private void waitForPipelineQuiescent(UUID tournamentId) throws InterruptedException {
        // Resolve expectedPrepared = totalPhases (all phases including siegerehrung per DEC-59
        // Clause E).
        // E51S18: siegerehrung now reaches PREPARED via vacuous L1+L2 execution.
        // totalPhases is stable after apply() commits (phases created synchronously in apply() TX).
        // If no phases exist (apply() failed or this is a defensive tearDown call with empty DB),
        // expectedPrepared = 0 → condition "prepared == 0" immediately true → safe return.
        Integer totalPhasesRaw =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        int totalPhases = (totalPhasesRaw == null) ? 0 : totalPhasesRaw;
        // E51S18 (DEC-59 Clause E): ALL phases reach PREPARED, including siegerehrung.
        // Pre-E51S18 used (totalPhases - 1) because siegerehrung stayed PENDING.
        int expectedPrepared = totalPhases;

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Integer preparedCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM phase WHERE tournament_id = ?"
                                    + " AND status = 'PREPARED'",
                            Integer.class,
                            tournamentId);
            int prepared = (preparedCount == null) ? 0 : preparedCount;

            if (prepared == expectedPrepared) {
                return;
            }
            Thread.sleep(50);
        }
        // Budget exhausted — fail with observable diagnostic
        // (AC-ERROR-HANDLING-QUIESCENCE-BUDGET-OBSERVABLE)
        var phaseRows =
                jdbcTemplate.queryForList(
                        "SELECT id, last_job_state, status FROM phase WHERE tournament_id = ?",
                        tournamentId);
        throw new AssertionError(
                "waitForPipelineQuiescent: pipeline did not quiesce within 5s for tournament "
                        + tournamentId
                        + " (expectedPrepared="
                        + expectedPrepared
                        + "). Phase rows (id, last_job_state, status): "
                        + phaseRows);
    }

    // =========================================================================
    // AC-TEST-APPLY-NO-PHASE-1-TEAMAVATARS-RED
    // =========================================================================

    /**
     * apply() for a tournament with 3 participating teams creates 3 Phase records and structural
     * TeamAvatars per DEC-59 Clauses A + B + E (E51S18 operationalization). After the async
     * background pipeline quiesces (DEC-55 D-3 + DEC-56 D-3 + DEC-59 Clause E), the equilibrium
     * status shape is:
     *
     * <ul>
     *   <li>Phase 1 (roundRobin) → MatchGen (L1) + RoundAssignment (L2) run → PREPARED
     *   <li>Phase 2 (roundRobin) → MatchGen (L1) + RoundAssignment (L2) run → PREPARED
     *   <li>Phase 3 (siegerehrung) → vacuous L1+L2 via SiegerehrungMatchGenerator (empty match
     *       list) → PREPARED (DEC-59 Clause E — uniform lifecycle via vacuous execution)
     * </ul>
     *
     * <p>Post-quiescence equilibrium (DEC-59 Clause E): {@code preparedCount=3, pendingCount=0,
     * totalPhases=3}. E51S17 contract ({@code preparedCount=2, pendingCount=1}) is SUPERSEDED by
     * DEC-59 Clause E.
     *
     * <p>Fixture: 3 participating teams (team_number 1-3), 2-group Phase 1 (roundRobin), 2-group
     * Phase 2 (roundRobin), 1-group siegerehrung Phase 3. Expected avatars per DEC-59 Clause A (N
     * avatars per phase including siegerehrung) + Clause B (teamId=NULL universally):
     *
     * <ul>
     *   <li>Phase 1: 3 avatars (N=3 participating teams, groupCount=2, teamId=NULL per Clause B)
     *   <li>Phase 2: 3 avatars (N=3 participating teams, teamId=NULL per Clause B)
     *   <li>Phase 3 (siegerehrung): 3 avatars (N=3 participating teams, groupNumber=1, rank-slots,
     *       teamId=NULL per Clauses A + B)
     * </ul>
     *
     * <p>Total: 9 avatars (3+3+3=9 per DEC-59 Clause A). Replaces the previous 3+4+0=7 shape which
     * was acknowledged-not-endorsed by E51S17.
     *
     * @see <a href="E51S18">E51S18 — DEC-59 operationalization (K-1+K-3+K-4+K-6)</a>
     * @see <a href="E51S17">E51S17 — Equilibrium contract alignment (superseded for
     *     siegerehrung)</a>
     * @see <a href="DEC-55">DEC-55 D-3 step 1 — MatchGenJobScheduledEvent per phase (now includes
     *     siegerehrung via DEC-59 Clause E)</a>
     * @see <a href="DEC-56">DEC-56 D-3 — L1+L2 always mandatory; matches reference avatar.getId()
     *     not teamId</a>
     * @see <a href="DEC-59">DEC-59 Clause A — N avatars per phase incl. siegerehrung; Clause B —
     *     teamId=NULL universally; Clause E — siegerehrung uniform lifecycle via vacuous L1+L2</a>
     */
    @Test
    @DisplayName(
            "apply() with 3 participating teams creates 3 phases and 9 structural avatars (3+3+3);"
                    + " post-quiescence equilibrium: preparedCount=3, pendingCount=0 (E51S18"
                    + " DEC-59 Clauses A+B+E)")
    void apply_withParticipatingTeams_creates3PhasesAndStructuralAvatars()
            throws InterruptedException {
        // Arrange: 3-phase config (section 3 is siegerehrung per E48S01 last-phase invariant)
        DraftConfig config =
                new DraftConfig(
                        List.of(
                                new DraftSection(
                                        1, "team_number", 2, "roundRobin", 0, 0, 15, 1, List.of()),
                                new DraftSection(
                                        2, "team_number", 2, "roundRobin", 0, 0, 15, 1, List.of()),
                                new DraftSection(
                                        3,
                                        "team_number",
                                        1,
                                        "siegerehrung",
                                        0,
                                        0,
                                        15,
                                        1,
                                        List.of())));

        // Act
        List<UUID> createdPhaseIds = draftService.apply(tournamentId, config);

        // Assert: 3 phases created
        assertThat(createdPhaseIds)
                .as("apply() must create exactly 3 phase records (one per section)")
                .hasSize(3);

        // DEC-26 Rule 2: verify phase count via assertj-db (independent of service read-path)
        Table phaseTable = assertDb.table("phase").build();
        Assertions.assertThat(phaseTable).hasNumberOfRows(3);

        // E51S17 GREEN: wait for pipeline quiescence BEFORE status queries.
        // AC-TEST-HELPER-SYMMETRY-WITH-SISTER: same call signature + ordering as
        // DefaultDraftServiceApplyAvatarsIT:176-177 (helper called between apply() and assertions).
        // AC-TEST-EQUILIBRIUM-CONTRACT-GREEN: ensures deterministic preparedCount=2, pendingCount=1
        // across 5 sequential runs (DEC-22 Q-1a Pattern B; DEC-55 D-3 + DEC-56 D-3).
        waitForPipelineQuiescent(tournamentId);

        // AC-TEST-EQUILIBRIUM-PHASE-STATUS-SHAPE: THREE independent counts — guards against
        // silent regressions where one phase enters FAILED/ASSIGNED without the binary
        // pendingCount==1 projection mismatching (E51S17 equilibrium contract).
        int preparedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase WHERE tournament_id = ? AND status ="
                                + " 'PREPARED'",
                        Integer.class,
                        tournamentId);
        int pendingCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase WHERE tournament_id = ? AND status = 'PENDING'",
                        Integer.class,
                        tournamentId);
        int totalPhases =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);

        // AC-TEST-EQUILIBRIUM-PHASE-STATUS-SHAPE: all THREE counts verified independently
        // to guard against silent regressions (e.g., a phase entering FAILED or ASSIGNED
        // without the binary pendingCount==0 projection mismatching).
        // E51S18 (DEC-59 Clause E): ALL phases including siegerehrung reach PREPARED via
        // vacuous L1+L2 execution (SiegerehrungMatchGenerator returns empty match list).
        assertThat(preparedCount)
                .as(
                        "Post-quiescence equilibrium: Phase 1 + Phase 2 (roundRobin) + Phase 3"
                                + " (siegerehrung) must all be PREPARED after L1+L2 completes"
                                + " per DEC-56 D-3 + DEC-59 Clause E (vacuous L1+L2 for"
                                + " siegerehrung via SiegerehrungMatchGenerator)")
                .isEqualTo(3);
        assertThat(pendingCount)
                .as(
                        "Post-quiescence equilibrium: no phase must stay PENDING — siegerehrung"
                                + " reaches PREPARED via vacuous L1+L2 per DEC-59 Clause E")
                .isEqualTo(0);
        assertThat(totalPhases)
                .as("Total phase count must be exactly 3 (one per DraftSection)")
                .isEqualTo(3);

        // DEC-59 Clause A + B: N avatars per phase including siegerehrung; teamId=NULL universally.
        // Fixture: N=3 participating teams, 3 phases (Phase1 + Phase2 + siegerehrung)
        //
        // Phase 1: 3 participating teams, groupCount=2, distributionMode=roundRobin
        //   → 3 avatars (N=3), teamId=NULL per Clause B
        //   round_robin groups: team-1→G1-P1, team-2→G2-P1, team-3→G1-P2
        UUID phase1Id = createdPhaseIds.get(0);
        Integer phase1AvatarCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase1Id);
        assertThat(phase1AvatarCount)
                .as(
                        "Phase 1 must have 3 structural avatars (N=3 per DEC-59 Clause A;"
                                + " not groupCount×posPerGroup)")
                .isEqualTo(3);

        // Phase 1: teamId must be NULL for all avatars per DEC-59 Clause B
        Integer phase1WithNullTeamId =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?"
                                + " AND team_id IS NULL",
                        Integer.class,
                        phase1Id);
        assertThat(phase1WithNullTeamId)
                .as(
                        "All Phase 1 avatars must have teamId=NULL at apply-time (DEC-59 Clause B —"
                                + " operator-confirmation is sole teamId trigger)")
                .isEqualTo(3);

        // Phase 2: 3 participating teams, groupCount=2, distributionMode=roundRobin
        //   → 3 avatars (N=3 per Clause A; was 4 = groupCount×ceil(3/2)=2×2), teamId=NULL
        UUID phase2Id = createdPhaseIds.get(1);
        Integer phase2AvatarCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase2Id);
        assertThat(phase2AvatarCount)
                .as(
                        "Phase 2 must have 3 structural avatars (N=3 per DEC-59 Clause A;"
                                + " was 4 = groupCount×ceil(N/groupCount) under DEC-55 D-1)")
                .isEqualTo(3);

        // Phase 3 (siegerehrung): 3 avatars (N=3 per Clause A; was 0), teamId=NULL
        UUID phase3Id = createdPhaseIds.get(2);
        Integer phase3AvatarCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase3Id);
        assertThat(phase3AvatarCount)
                .as(
                        "Phase 3 (siegerehrung) must have 3 structural avatars (N=3 per DEC-59"
                                + " Clause A — siegerehrung is no longer skipped; was 0)")
                .isEqualTo(3);

        // Total: 3 + 3 + 3 = 9 avatars (DEC-59 Clause A: 3+3+3=9, not 3+4+0=7)
        Table avatarTable = assertDb.table("team_avatar").build();
        Assertions.assertThat(avatarTable).hasNumberOfRows(9);
    }
}
