package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.slotopt.SlotResultApplicator;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression-guard integration test for E51S12 NF-MED-2 — lap-permutation preserves referee
 * invariant (AC-TEST-LAP-PERMUTATION-PRESERVES-REFEREE-INVARIANT-RED; DEC-56 § NF-MED-2 Impact).
 *
 * <h2>Structural assumption being tested</h2>
 *
 * <p>After {@code RefereeAssigner.assignReferees(phaseId)} has run (setting {@code
 * match.refereeTeamId} on all matches), L3 ({@link SlotResultApplicator}) re-applies a
 * lap-permutation to change {@code lapNumber}/{@code fieldNumber} on matches. The structural
 * invariant: the lap-permutation changes ONLY {@code lapNumber} and {@code fieldNumber} — it does
 * NOT modify {@code refereeTeamId}. Therefore:
 *
 * <ol>
 *   <li>Every match's {@code refereeTeamId} is unchanged after L3 lap-permutation.
 *   <li>The per-lap referee constraint (referee team is not in the playing teams set of that lap)
 *       is preserved structurally: the permutation re-orders whole matches (including both
 *       player-avatars and referee) within their laps.
 * </ol>
 *
 * <p>This is a "structural-assumption: lap-permutation preserves per-lap team-membership"
 * regression guard per AC-TEST-LAP-PERMUTATION-PRESERVES-REFEREE-INVARIANT-RED. If a future L3
 * implementation breaks this assumption (e.g., by re-assigning referees based on new lap numbers),
 * this test will fail — alerting the developer to re-verify the NF-MED-2 invariant.
 *
 * <h2>Test approach</h2>
 *
 * <p>Uses {@link PhaseToRawPhaseDefMapper} to get the {@link MappingResult} for the phase, then
 * invokes {@link SlotResultApplicator#applyResult(long, int, MappingResult)} with a non-identity
 * permutation rank, then asserts that {@code referee_team_id} values on all matches are unchanged
 * via direct JDBC verification.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 — Regression-guard test authored for the post-E51S11 world.
 *   <li>DEC-44 — Bounded-context IT: uses {@code @SpringBootTest(webEnvironment = NONE)}.
 *   <li>DEC-56 § NF-MED-2 — lap-permutation preserves referee invariant.
 * </ul>
 *
 * @see SlotResultApplicator
 * @see PhaseToRawPhaseDefMapper
 * @see de.vvwt.tm.tournament.internal.referee.RefereeAssigner
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-44">DEC-44 — IT annotation convention</a>
 * @see <a href="DEC-56">DEC-56 § NF-MED-2 — lap-permutation preserves referee invariant</a>
 * @see <a href="E51S12">E51S12 — NF-MED-2 regression guard</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:lappermutation-ref-invariant-it;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.fallback.field-count=1"
        })
@ActiveProfiles("test")
@Import({
    TenantContextTestSupport.class,
    LapPermutationPreservesRefereeInvariantIT.TestConfig.class
})
@DisplayName(
        "LapPermutationPreservesRefereeInvariantIT — NF-MED-2: lap-permutation preserves"
                + " refereeTeamId")
class LapPermutationPreservesRefereeInvariantIT {

    @TestConfiguration
    static class TestConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {};
        }

        @Bean
        @Primary
        SimpMessagingTemplate testSimpMessagingTemplate() {
            return mock(SimpMessagingTemplate.class);
        }
    }

    /** L3 result applicator — the subject under test for the NF-MED-2 invariant. */
    @Autowired private SlotResultApplicator slotResultApplicator;

    /** Forward mapper — used to build the MappingResult needed by SlotResultApplicator. */
    @Autowired private PhaseToRawPhaseDefMapper phaseToRawPhaseDefMapper;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    // ── Fixture state ─────────────────────────────────────────────────────────

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    /** Referee team UUID — pre-assigned to matches (simulating post-assignReferees state). */
    private UUID refereeTeamId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "NF-MED-2 IT Location");

        // Tournament with fieldCount=1 so slot layout is simple: each match = 1 lap, 1 field
        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "NF-MED-2 IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                1 /* fieldCount=1: each match occupies exactly 1 lap */,
                4,
                true);

        // Phase with 4 avatars in 1 group → round-robin: C(4,2) = 6 matches
        // With fieldCount=1: each match gets its own lap (0,1,2,3,4,5) — 6 laps total
        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " optimized, last_job_state)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "NF-MED-2 IT Phase",
                "PREPARED",
                false,
                null);

        // Referee team (referee_assignment=true)
        refereeTeamId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " referee_assignment, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                refereeTeamId,
                tournamentId,
                99,
                "Referee Team",
                false,
                true,
                LocalDateTime.now());

        // 4 avatar placeholders (structural identity per DEC-9)
        UUID av1 = insertAvatar(1, 1);
        UUID av2 = insertAvatar(1, 2);
        UUID av3 = insertAvatar(1, 3);
        UUID av4 = insertAvatar(1, 4);

        // 6 round-robin matches with:
        //   - Non-null lap_number and field_number (post-L2 state — DEC-56 D-1)
        //   - referee_team_id set (post-assignReferees state)
        // fieldCount=1: match at flat index i → lap=i, field=0
        insertMatchWithReferee(av1, av2, 0, 0, refereeTeamId); // lap 0
        insertMatchWithReferee(av1, av3, 1, 0, refereeTeamId); // lap 1
        insertMatchWithReferee(av1, av4, 2, 0, refereeTeamId); // lap 2
        insertMatchWithReferee(av2, av3, 3, 0, refereeTeamId); // lap 3
        insertMatchWithReferee(av2, av4, 4, 0, refereeTeamId); // lap 4
        insertMatchWithReferee(av3, av4, 5, 0, refereeTeamId); // lap 5
    }

    private UUID insertAvatar(int groupNumber, int groupPosition) {
        UUID avatarId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, NULL)",
                avatarId,
                tournamentId,
                phaseId,
                groupNumber,
                groupPosition);
        return avatarId;
    }

    private void insertMatchWithReferee(
            UUID memberAvatar1Id,
            UUID memberAvatar2Id,
            int lapNumber,
            int fieldNumber,
            UUID refId) {
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, lap_number, field_number,"
                        + " referee_team_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                tournamentId,
                phaseId,
                memberAvatar1Id,
                memberAvatar2Id,
                0,
                1,
                lapNumber,
                fieldNumber,
                refId,
                LocalDateTime.now());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-LAP-PERMUTATION-PRESERVES-REFEREE-INVARIANT-RED
    // =========================================================================

    /**
     * Structural-assumption regression guard (NF-MED-2): after L3 lap-permutation (via {@link
     * SlotResultApplicator#applyResult(long, int, MappingResult)}), every match's {@code
     * refereeTeamId} is unchanged.
     *
     * <p>Test uses a non-identity permutation rank (non-zero) for 6 laps, which produces a
     * different lap ordering than the L2 baseline. After the permutation, {@code lapNumber} and
     * {@code fieldNumber} change on matches, but {@code referee_team_id} must remain equal to the
     * pre-L3 value ({@code refereeTeamId}) for every match.
     *
     * <p>The structural invariant: {@code SlotResultApplicator.applyResult} writes ONLY {@code
     * lapNumber} and {@code fieldNumber}. It does NOT touch {@code refereeTeamId}. This is the
     * NF-MED-2 regression guard: if a future L3 implementation incorrectly re-assigns referees
     * based on post-permutation lap numbers, this test will fail.
     *
     * <p>Structural assumption name (per AC wording): "structural assumption: lap-permutation
     * preserves per-lap team-membership"
     *
     * <p>Was RED before E51S11 (SlotResultApplicator didn't implement the flat-index algorithm);
     * expected GREEN after E51S11 lands.
     */
    @Test
    @DisplayName(
            "structural assumption: lap-permutation preserves per-lap team-membership —"
                    + " refereeTeamId unchanged after L3 lap-permutation"
                    + " (AC-TEST-LAP-PERMUTATION-PRESERVES-REFEREE-INVARIANT-RED; DEC-56 NF-MED-2)")
    void lapPermutation_preservesRefereeTeamId_onAllMatches() {
        // Precondition: all 6 matches have refereeTeamId set (post-assignReferees state)
        List<Map<String, Object>> beforeRows =
                jdbcTemplate.queryForList(
                        "SELECT id, lap_number, field_number, referee_team_id"
                                + " FROM match WHERE phase_id = ? ORDER BY lap_number",
                        phaseId);
        assertThat(beforeRows).hasSize(6);
        assertThat(beforeRows)
                .allSatisfy(
                        row ->
                                assertThat(row.get("referee_team_id"))
                                        .as("Precondition: refereeTeamId must be set before L3")
                                        .isNotNull());

        // Act: build the MappingResult from the DB state (post-L2 layout),
        // then apply a non-identity lap permutation (rank=1 → non-identity for 6 laps)
        MappingResult mapping = phaseToRawPhaseDefMapper.map(phaseId);
        int fieldCount = phaseToRawPhaseDefMapper.getFieldCount();

        // rank=1 → non-identity permutation for 6! permutations (different from identity rank=0)
        slotResultApplicator.applyResult(1L, fieldCount, mapping);

        // Assert invariant (a): all matches still have refereeTeamId = refereeTeamId (unchanged)
        List<Map<String, Object>> afterRows =
                jdbcTemplate.queryForList(
                        "SELECT id, lap_number, field_number, referee_team_id"
                                + " FROM match WHERE phase_id = ? ORDER BY lap_number",
                        phaseId);
        assertThat(afterRows).hasSize(6);
        assertThat(afterRows)
                .as(
                        "AC-TEST-LAP-PERMUTATION-PRESERVES-REFEREE-INVARIANT-RED:"
                                + " every match's refereeTeamId must be unchanged after L3"
                                + " lap-permutation (NF-MED-2 structural assumption;"
                                + " SlotResultApplicator.applyResult writes ONLY lap+field)")
                .allSatisfy(
                        row ->
                                assertThat(row.get("referee_team_id"))
                                        .as("refereeTeamId must be unchanged by lap-permutation")
                                        .isEqualTo(refereeTeamId));

        // Assert structural: lap numbers are still a permutation of [0..5] — permutation ran
        assertThat(afterRows.stream().map(r -> ((Number) r.get("lap_number")).intValue()).toList())
                .as(
                        "Lap numbers after permutation must still be [0..5] (same values,"
                                + " different order — permutation is valid)")
                .containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5);
    }
}
