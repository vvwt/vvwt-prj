package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first IT: {@code DefaultMatchRepository.findByPhaseId} must return matches ordered by {@code
 * lap_number ASC NULLS LAST, field_number ASC NULLS LAST, id ASC} (E54S13 determinism fix).
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-ORDER-BY-DETERMINISM-RED (primary ORDER BY gate for E54S13)
 * </ul>
 *
 * <h2>RED state</h2>
 *
 * <p>Prior to the E54S13 fix, {@code SELECT_BY_PHASE} has no ORDER BY — result order is
 * non-deterministic. This test fails RED because the sort contract is violated. After E54S13 adds
 * {@code ORDER BY lap_number ASC NULLS LAST, field_number ASC NULLS LAST, id ASC}, the test turns
 * GREEN.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 — Q-1a fresh-RED (written before fix)
 *   <li>DEC-26 Rule 3 — fixture via direct JDBC, not repository methods
 *   <li>DEC-44 — {@code @SpringBootTest(NONE, classes = TournamentManagerApplication.class)}
 *   <li>AC-SECURITY-NO-PII-IN-NEW-TESTS: synthetic IDs and team names
 *   <li>AC-SECURITY-NO-TENANT-BLEED: tenant context bound/unbound in BeforeEach/AfterEach
 * </ul>
 *
 * @see de.vvwt.tm.tournament.internal.DefaultMatchRepository
 * @see <a href="E54S13">E54S13 — findByPhaseId ORDER BY determinism</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e54s13-match-order-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DefaultMatchRepositoryOrderIT — E54S13 — findByPhaseId must return ordered results")
class DefaultMatchRepositoryOrderIT {

    @Autowired
    @Qualifier("tmMatchRepository")
    private MatchRepository matchRepository;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tournamentId;
    private UUID phaseId;
    private UUID avatar1Id;
    private UUID avatar2Id;
    private UUID avatar3Id;
    private UUID avatar4Id;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        UUID locationId = tenantBinder.getDefaultLocationId();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        avatar1Id = UUID.randomUUID();
        avatar2Id = UUID.randomUUID();
        avatar3Id = UUID.randomUUID();
        avatar4Id = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                tournamentId,
                locationId,
                "E54S13 Order IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED");

        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number,"
                        + " description, status, current_lap_number)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PREPARED",
                0);

        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();
        UUID teamId3 = UUID.randomUUID();
        UUID teamId4 = UUID.randomUUID();
        for (Object[] row :
                new Object[][] {
                    {teamId1, 1, "Team A"},
                    {teamId2, 2, "Team B"},
                    {teamId3, 3, "Team C"},
                    {teamId4, 4, "Team D"}
                }) {
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description)"
                            + " VALUES (?, ?, ?, ?)",
                    row[0],
                    tournamentId,
                    row[1],
                    row[2]);
        }
        int pos = 1;
        for (Object[] row :
                new Object[][] {
                    {avatar1Id, teamId1}, {avatar2Id, teamId2},
                    {avatar3Id, teamId3}, {avatar4Id, teamId4}
                }) {
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id,"
                            + " group_number, group_position)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    row[0],
                    tournamentId,
                    phaseId,
                    row[1],
                    1,
                    pos++);
        }
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-ORDER-BY-DETERMINISM-RED: findByPhaseId must return matches sorted by lap_number ASC
     * NULLS LAST, field_number ASC NULLS LAST, id ASC.
     *
     * <p>Inserts 4 matches with known (lap_number, field_number) assignments in non-sorted order.
     * Asserts the returned list is sorted by (lap_number, field_number, id).
     *
     * <p>RED: without ORDER BY, H2 returns rows in insertion order (lap2/f2 before lap1/f1). GREEN:
     * ORDER BY produces lap1/f1, lap1/f2, lap2/f1, lap2/f2.
     */
    @Test
    @DisplayName("findByPhaseId returns matches ordered by lap_number, field_number, id")
    void findByPhaseId_returnsMatchesOrderedByLapAndField_E54S13() {
        // Insert in deliberate non-sorted order: lap2/f2, lap1/f2, lap2/f1, lap1/f1
        UUID matchLap2Field2 = UUID.randomUUID();
        UUID matchLap1Field2 = UUID.randomUUID();
        UUID matchLap2Field1 = UUID.randomUUID();
        UUID matchLap1Field1 = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                        + " lap_number, field_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchLap2Field2,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                0,
                3,
                2,
                2);
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                        + " lap_number, field_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchLap1Field2,
                tournamentId,
                phaseId,
                avatar2Id,
                avatar3Id,
                0,
                3,
                1,
                2);
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                        + " lap_number, field_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchLap2Field1,
                tournamentId,
                phaseId,
                avatar3Id,
                avatar4Id,
                0,
                3,
                2,
                1);
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                        + " lap_number, field_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchLap1Field1,
                tournamentId,
                phaseId,
                avatar4Id,
                avatar1Id,
                0,
                3,
                1,
                1);

        List<Match> result = matchRepository.findByPhaseId(phaseId);

        assertThat(result)
                .as(
                        "AC-TEST-ORDER-BY-DETERMINISM-RED (E54S13): findByPhaseId must return"
                                + " matches ordered by lap_number ASC NULLS LAST, field_number ASC"
                                + " NULLS LAST, id ASC. Expected order: lap1/f1, lap1/f2, lap2/f1,"
                                + " lap2/f2. Without ORDER BY, H2 returns insertion order"
                                + " (non-deterministic).")
                .hasSize(4);

        // Assert sorted order: lap1/f1, lap1/f2, lap2/f1, lap2/f2
        assertThat(result.get(0).getId())
                .as("position 0 must be lap1/field1")
                .isEqualTo(matchLap1Field1);
        assertThat(result.get(1).getId())
                .as("position 1 must be lap1/field2")
                .isEqualTo(matchLap1Field2);
        assertThat(result.get(2).getId())
                .as("position 2 must be lap2/field1")
                .isEqualTo(matchLap2Field1);
        assertThat(result.get(3).getId())
                .as("position 3 must be lap2/field2")
                .isEqualTo(matchLap2Field2);
    }

    /**
     * AC-TEST-ORDER-BY-NULL-LAPS-LAST: matches with null lap_number must appear AFTER assigned
     * matches (NULLS LAST semantics).
     */
    @Test
    @DisplayName("findByPhaseId returns null-lap matches after assigned matches (NULLS LAST)")
    void findByPhaseId_nullLapsAppearLast_E54S13() {
        UUID matchNullLap = UUID.randomUUID();
        UUID matchLap1 = UUID.randomUUID();

        // Insert null-lap first (would appear first without NULLS LAST on some engines)
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                matchNullLap,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                0,
                3);
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                        + " lap_number, field_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchLap1,
                tournamentId,
                phaseId,
                avatar2Id,
                avatar3Id,
                0,
                3,
                1,
                1);

        List<Match> result = matchRepository.findByPhaseId(phaseId);

        assertThat(result)
                .as("AC-TEST-ORDER-BY-NULL-LAPS-LAST (E54S13): null-lap matches must appear last")
                .hasSize(2);
        assertThat(result.get(0).getId())
                .as("position 0 must be the assigned-lap match (lap_number=1)")
                .isEqualTo(matchLap1);
        assertThat(result.get(1).getId())
                .as("position 1 must be the null-lap match")
                .isEqualTo(matchNullLap);
    }
}
