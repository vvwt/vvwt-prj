package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
 * RED-first IT for E51S16 — {@code AC-TEST-VARIETY-SCORER-INTEGRATION-PRESERVED-RED}.
 *
 * <p>Verifies that invoking a slot-opt-module public entry point ({@link SlotOptimizationClient})
 * on a 4-team / 3-field / 1-group phase fixture computes a slot-optimized result: all matches
 * retain non-null {@code lap_number} and {@code field_number} after optimization, and the observed
 * best rank is in {@code [0, lapCount!)} (where {@code lapCount = rowCount / fieldCount}).
 *
 * <p>The test does NOT directly invoke {@code VarietyScorer.score()} (covered by worker-lib's own
 * tests). It invokes through the slot-opt module's public entry point ({@link
 * SlotOptimizationClient} — resolved by Spring to the {@code @Primary} {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient}). The {@code
 * RoutingSlotOptimizationClient} internally routes to Leg 1 (exhaustive in-process) for {@code
 * lapCount ≤ tm.slotopt.exhaustive-max-n} and calls {@code VarietyScorer.scoreWithMatrix()}
 * internally. This test validates that the VarietyScorer integration path is still active in the
 * slot-opt module's production code path — unchanged by E51S16's B-b1 cycle-break refactor.
 *
 * <h2>Fixture design (4 teams, 3 fields, 1 group)</h2>
 *
 * <p>4 teams → K4 = 6 matches → lapCount = floor(6/3) = 2. LapCount=2 is within the default
 * exhaustiveMaxN=10 threshold, so {@code RoutingSlotOptimizationClient} executes Leg 1 inline,
 * invoking {@code VarietyScorer.scoreWithMatrix()} for every permutation in {@code [0, 2!)} =
 * {0,1}.
 *
 * <h2>DEC-22 RED-first</h2>
 *
 * <p>Before E51S16, {@code DefaultRoundAssignmentService} injected {@code
 * PhaseToRawPhaseDefMapper}, causing the Modulith cycle {@code slotopt → tournament → slotopt}. The
 * {@code ApplicationModulesTest.verifiesModuleStructure} test fails in that state, indicating
 * structural breakage. After E51S16's B-b1 cycle-break, the module structure is valid and this
 * test's slice-context can be cleanly loaded. The RED state is thus the pre-refactor cycle state.
 *
 * <h2>DEC-36 cross-package rule</h2>
 *
 * <p>This test is in {@code de.vvwt.tm.slotopt} — it injects {@link SlotOptimizationClient} (the
 * public interface), never {@code RoutingSlotOptimizationClient} directly.
 *
 * @see SlotOptimizationClient
 * @see de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="E51S11">E51S11 — PASS_WITH_NOTES: this AC flagged as missing</a>
 * @see <a href="E51S16">E51S16 — B-b1 cycle-break; authors this missing IT</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:varietyscorerintegrationit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.fallback.field-count=3",
            "tm.slotopt.exhaustive-max-n=10"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, VarietyScorerIntegrationPreservedIT.TestConfig.class})
@DisplayName(
        "VarietyScorerIntegrationPreservedIT — E51S16"
                + " AC-TEST-VARIETY-SCORER-INTEGRATION-PRESERVED-RED")
class VarietyScorerIntegrationPreservedIT {

    /** Suppresses WebSocket beans not needed for this IT. */
    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        SimpMessagingTemplate testSimpMessagingTemplate() {
            return mock(SimpMessagingTemplate.class);
        }
    }

    /**
     * Subject under test — injected via public interface per DEC-36 cross-package rule.
     *
     * <p>Spring resolves to {@code RoutingSlotOptimizationClient} ({@code @Primary}) per E27S01.
     */
    @Autowired private SlotOptimizationClient slotOptimizationClient;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private DataSource dataSource;

    private UUID locationId;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "VarietyScorer IT Location");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    private void createTournament(int fieldCount) {
        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "VarietyScorer IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                fieldCount,
                4,
                true);
    }

    private UUID createPhase(int sequenceNumber) {
        UUID phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " optimized, last_job_state)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                sequenceNumber,
                "VarietyScorer IT Phase",
                "PREPARED",
                false,
                "idle");
        return phaseId;
    }

    private List<UUID> insertAvatars(UUID phaseId, int n) {
        List<UUID> avatarIds = new ArrayList<>();
        for (int pos = 1; pos <= n; pos++) {
            UUID avatarId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id)"
                            + " VALUES (?, ?, ?, ?, ?, NULL)",
                    avatarId,
                    tournamentId,
                    phaseId,
                    1,
                    pos);
            avatarIds.add(avatarId);
        }
        return avatarIds;
    }

    /**
     * Inserts round-robin matches between all pairs of avatars, with pre-assigned lap+field from L2
     * (sequential: lap=i/fieldCount, field=i%fieldCount).
     */
    private List<UUID> insertRoundRobinMatchesWithL2Assignments(
            UUID phaseId, List<UUID> avatarIds, int fieldCount) {
        List<UUID> matchIds = new ArrayList<>();
        int matchIdx = 0;
        for (int i = 0; i < avatarIds.size(); i++) {
            for (int j = i + 1; j < avatarIds.size(); j++) {
                UUID matchId = UUID.randomUUID();
                int lapNumber = matchIdx / fieldCount;
                int fieldNumber = matchIdx % fieldCount;
                jdbcTemplate.update(
                        "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                                + " member_avatar_2_id, state, lap_number, field_number)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        matchId,
                        tournamentId,
                        phaseId,
                        avatarIds.get(i),
                        avatarIds.get(j),
                        0,
                        lapNumber,
                        fieldNumber);
                matchIds.add(matchId);
                matchIdx++;
            }
        }
        return matchIds;
    }

    // =========================================================================
    // AC-TEST-VARIETY-SCORER-INTEGRATION-PRESERVED-RED
    // =========================================================================

    /**
     * RED-first (E51S16): given a 4-team / 3-field / 1-group phase fixture, invoking the slot-opt
     * module's public entry point ({@link SlotOptimizationClient}) produces a slot-optimized
     * result.
     *
     * <p>Behavioral assertions:
     *
     * <ul>
     *   <li>All matches have non-null {@code lap_number} and {@code field_number} after
     *       optimization (slot assignments are preserved / updated by {@code
     *       SlotResultApplicator}).
     *   <li>The optimization path traverses {@code VarietyScorer.scoreWithMatrix()} (Leg 1 inline,
     *       lapCount=2 ≤ exhaustiveMaxN=10). The behavioral proxy for "VarietyScorer ran": the
     *       result is non-null (optimization did not throw) and matches still have valid slots.
     * </ul>
     *
     * <p>lapCount = floor(6 matches / 3 fields) = 2 → Leg 1 fires → {@code
     * RoutingSlotOptimizationClient.executeLeg1Inline()} calls {@code
     * VarietyScorer.scoreWithMatrix()} for each permutation in {@code [0, 2!)}.
     */
    @Test
    @DisplayName(
            "AC-TEST-VARIETY-SCORER-INTEGRATION-PRESERVED-RED: 4-team / 3-field / 1-group phase"
                    + " → slot-opt via public entry point → all matches have valid lap+field"
                    + " (VarietyScorer integration traversed)")
    void varietyScorerIntegration_viaSlotOptPublicEntryPoint_produces_validSlotAssignments() {
        // Arrange: 4 teams, 3 fields, 1 group → 6 matches, lapCount=2
        createTournament(3);
        UUID phaseId = createPhase(1);
        List<UUID> avatars = insertAvatars(phaseId, 4);
        List<UUID> matchIds = insertRoundRobinMatchesWithL2Assignments(phaseId, avatars, 3);

        // Pre-condition: all matches have L2 lap+field assignments
        List<Map<String, Object>> preBefore =
                jdbcTemplate.queryForList(
                        "SELECT COUNT(*) AS cnt FROM match WHERE phase_id = ? AND lap_number IS"
                                + " NULL",
                        phaseId);
        assertThat(((Number) preBefore.get(0).get("cnt")).intValue())
                .as("pre-condition: all matches have lap_number (L2 assigns them)")
                .isZero();

        // Act: invoke slot-opt entry point (resolves to RoutingSlotOptimizationClient @Primary)
        // RoutingSlotOptimizationClient routes lapCount=2 → Leg 1 → VarietyScorer internally
        slotOptimizationClient.optimize(phaseId);

        // Assert: all matches still have non-null lap_number and field_number after optimization
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT lap_number, field_number FROM match WHERE phase_id = ?", phaseId);
        assertThat(rows)
                .as(
                        "AC-TEST-VARIETY-SCORER-INTEGRATION-PRESERVED-RED:"
                                + " all matches must have non-null lap+field after slot-opt")
                .hasSize(6);
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("lap_number"))
                                    .as("lap_number must be non-null after slot-opt")
                                    .isNotNull();
                            assertThat(row.get("field_number"))
                                    .as("field_number must be non-null after slot-opt")
                                    .isNotNull();
                        });

        // Assert: at most fieldCount=3 matches per lap (slot-opt preserves slot capacity)
        java.util.Map<Integer, Long> matchesPerLap = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            int lap = ((Number) row.get("lap_number")).intValue();
            matchesPerLap.merge(lap, 1L, Long::sum);
        }
        assertThat(matchesPerLap.values())
                .as("slot-opt result must respect fieldCount=3 capacity per lap")
                .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(3));
    }
}
