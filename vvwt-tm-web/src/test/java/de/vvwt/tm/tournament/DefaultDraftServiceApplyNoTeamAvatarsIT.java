package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first DAO IT for {@code DefaultDraftService.apply()} — verifies that Phase-1-Distribution is
 * removed: apply() creates Phase records ONLY, NO TeamAvatars (E48S17,
 * AC-TEST-APPLY-NO-PHASE-1-TEAMAVATARS-RED).
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
 * @see <a href="E48S17">E48S17 — AC-TEST-APPLY-NO-PHASE-1-TEAMAVATARS-RED</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-46">DEC-46 — DEC-26 scope extension to all vvwt-prj modules</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:applynoteavatarit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DefaultDraftService apply() — no Phase-1 TeamAvatars IT — E48S17 RED-first")
class DefaultDraftServiceApplyNoTeamAvatarsIT {

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
                "PLANNED",
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
    void tearDown() {
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-APPLY-NO-PHASE-1-TEAMAVATARS-RED
    // =========================================================================

    /**
     * AC-TEST-APPLY-NO-PHASE-1-TEAMAVATARS-RED: apply() for a tournament with 3 participating teams
     * creates 3 Phase records (status=PENDING) and 0 TeamAvatars.
     *
     * <p>RED-first: before the isFirstPhase branch is removed, apply() would create TeamAvatars for
     * Phase 1. This test verifies the target behavior (0 TeamAvatars) after the branch removal.
     */
    @Test
    @DisplayName(
            "apply() with 3 participating teams creates 3 PENDING phases and 0 TeamAvatars"
                    + " (AC-TEST-APPLY-NO-PHASE-1-TEAMAVATARS-RED)")
    void apply_withParticipatingTeams_creates3PhasesAnd0TeamAvatars() {
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

        // DEC-26 Rule 2: verify 0 TeamAvatars via assertj-db
        // (before E48S17 patch, this would fail because apply() creates Phase-1 avatars)
        Table avatarTable = assertDb.table("team_avatar").build();
        Assertions.assertThat(avatarTable).hasNumberOfRows(0);

        // Verify all 3 phases are PENDING
        int pendingCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase WHERE tournament_id = ? AND status = 'PENDING'",
                        Integer.class,
                        tournamentId);
        assertThat(pendingCount)
                .as("All 3 phases must have status PENDING after apply()")
                .isEqualTo(3);
    }
}
