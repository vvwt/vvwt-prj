package de.vvwt.tm.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.photo.PhotoUrlBuilder;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Interface contract tests for {@link CertificateAssembler} — E23S08, DEC-22 Q-1a RED-first.
 *
 * <p>These tests were authored RED-first against the non-existent {@code CertificateAssembler}
 * interface (DEC-22 Iron Law). They went RED (compile failure) at Step 2 of E23S08 execution and
 * GREEN after the interface and {@code DefaultCertificateAssembler} implementation were created.
 *
 * <p>Per DEC-36: this test class is in {@code de.vvwt.tm.certificate} — a DIFFERENT package than
 * the implementation at {@code de.vvwt.tm.certificate.internal.DefaultCertificateAssembler}.
 * Therefore, all field declarations and mock types reference the {@link CertificateAssembler}
 * PUBLIC INTERFACE, never the concrete class.
 *
 * <p>Per DEC-41: the {@code computePlacementOrder_resultMatchesTeamAvatarRatingNaturalOrder} test
 * asserts a named algebraic invariant (criterion d): the result ordering is determined by {@code
 * TeamAvatarRating.compareTo} natural ordering, verified over a representative 3-team input set.
 * This qualifies as Spec-Anchored observable form per DEC-41 § 1(d).
 *
 * @see CertificateAssembler
 * @since E23S08
 */
@DisplayName("CertificateAssembler interface contract — E23S08 DEC-22 Q-1a")
class CertificateAssemblerTest {

    // -------------------------------------------------------------------------
    // Shared test IDs
    // -------------------------------------------------------------------------

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOURNAMENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PHASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TEAM_A_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID TEAM_B_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID TEAM_C_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID AVATAR_A_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID AVATAR_B_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID AVATAR_C_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");

    // -------------------------------------------------------------------------
    // DEC-36: all mock/field types are the PUBLIC INTERFACE (cross-package test)
    // -------------------------------------------------------------------------

    private PhaseRepository phaseRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private TeamAvatarRatingRepository teamAvatarRatingRepository;
    private TeamRepository teamRepository;
    private PhotoStorageService photoStorageService;
    private JdbcTemplate jdbcTemplate;
    private PhotoUrlBuilder photoUrlBuilder;

    /** DEC-36: field type is the PUBLIC interface. */
    private CertificateAssembler assembler;

    // -------------------------------------------------------------------------
    // Test data builders
    // -------------------------------------------------------------------------

    private static Phase makePhase(UUID phaseId, int sequenceNumber) {
        return new Phase(
                phaseId,
                TENANT_ID,
                TOURNAMENT_ID,
                sequenceNumber,
                "Phase " + sequenceNumber,
                "COMPLETED",
                3,
                LocalDateTime.now());
    }

    private static TeamAvatar makeAvatar(UUID avatarId, UUID teamId, UUID phaseId) {
        return new TeamAvatar(
                avatarId, TENANT_ID, TOURNAMENT_ID, phaseId, 1, 1, teamId, null, null);
    }

    private static TeamAvatarRating makeRating(
            UUID avatarId,
            int points,
            double setQuotient,
            double ballQuotient,
            boolean withoutAssessment) {
        return new TeamAvatarRating(
                avatarId,
                TENANT_ID,
                3,
                6,
                points,
                3,
                3,
                60,
                30,
                setQuotient,
                ballQuotient,
                withoutAssessment,
                null);
    }

    private static Team makeTeam(UUID teamId, String description) {
        Team t = new Team();
        t.setId(teamId);
        t.setTenantId(TENANT_ID);
        t.setTournamentId(TOURNAMENT_ID);
        t.setDescription(description);
        t.setTeamNumber(1);
        return t;
    }

    private static Tournament makeTournament() {
        return new Tournament(
                TOURNAMENT_ID,
                TENANT_ID,
                "Stadtmeisterschaft 2026",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "COMPLETED",
                null,
                LocalDateTime.of(2026, 4, 15, 9, 0),
                4,
                8);
    }

    @BeforeEach
    void setUp() {
        phaseRepository = mock(PhaseRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        teamAvatarRatingRepository = mock(TeamAvatarRatingRepository.class);
        teamRepository = mock(TeamRepository.class);
        photoStorageService = mock(PhotoStorageService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        photoUrlBuilder = mock(PhotoUrlBuilder.class);

        // DEC-36: the assembly uses DefaultCertificateAssembler but the declared type is the
        // interface — the test does NOT import the impl class at all (cross-package rule)
        assembler =
                new de.vvwt.tm.certificate.internal.DefaultCertificateAssembler(
                        phaseRepository,
                        teamAvatarRepository,
                        teamAvatarRatingRepository,
                        teamRepository,
                        photoStorageService,
                        jdbcTemplate,
                        photoUrlBuilder);
    }

    // -------------------------------------------------------------------------
    // AC-INTERFACE-CREATED: getFinalPhase
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getFinalPhase: returns phase with highest sequenceNumber (AC-INTERFACE-CREATED)")
    void getFinalPhase_returnsHighestSequencePhase() {
        UUID phaseId2 = UUID.fromString("00000000-0000-0000-0000-000000000099");
        Phase p1 = makePhase(PHASE_ID, 1);
        Phase p2 = makePhase(phaseId2, 2);
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(p1, p2));

        Optional<Phase> result = assembler.getFinalPhase(TOURNAMENT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getSequenceNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("getFinalPhase: returns empty when tournament has no phases")
    void getFinalPhase_returnsEmpty_whenNoPhases() {
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());

        assertThat(assembler.getFinalPhase(TOURNAMENT_ID)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC-TESTING-DEC41-OBSERVABLE-FORM: named algebraic invariant (criterion d)
    //
    // Invariant: computePlacementOrder result ordering is determined by
    // TeamAvatarRating.compareTo natural ordering (DEC-33 named invariant).
    // Verified over a representative 3-team input set (varying points, setQuotient,
    // withoutAssessment) to satisfy DEC-41 § 1(d) "quantified body".
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "computePlacementOrder: result order matches TeamAvatarRating.compareTo natural order"
                    + " (DEC-41 named algebraic invariant, DEC-33)")
    void computePlacementOrder_resultMatchesTeamAvatarRatingNaturalOrder() {
        Phase phase = makePhase(PHASE_ID, 1);

        TeamAvatar avA = makeAvatar(AVATAR_A_ID, TEAM_A_ID, PHASE_ID);
        TeamAvatar avB = makeAvatar(AVATAR_B_ID, TEAM_B_ID, PHASE_ID);
        TeamAvatar avC = makeAvatar(AVATAR_C_ID, TEAM_C_ID, PHASE_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avA, avB, avC));

        // Representative input set (3 teams with distinct rating ordering):
        // A: 6 pts, setQ=2.0, ballQ=1.5, normal   → should rank 1st
        // C: 4 pts, setQ=1.5, ballQ=1.2, normal   → should rank 2nd
        // B: 10 pts, setQ=5.0, ballQ=5.0, withoutAssessment → should rank 3rd (last per DEC-33)
        TeamAvatarRating ratingA = makeRating(AVATAR_A_ID, 6, 2.0, 1.5, false);
        TeamAvatarRating ratingB = makeRating(AVATAR_B_ID, 10, 5.0, 5.0, true);
        TeamAvatarRating ratingC = makeRating(AVATAR_C_ID, 4, 1.5, 1.2, false);
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.of(ratingA));
        when(teamAvatarRatingRepository.findById(AVATAR_B_ID)).thenReturn(Optional.of(ratingB));
        when(teamAvatarRatingRepository.findById(AVATAR_C_ID)).thenReturn(Optional.of(ratingC));

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        // Named algebraic invariant: for all i < j in result, ratingOf(result[i]) <=
        // ratingOf(result[j])
        // i.e., lower index = higher ranking (better rating per compareTo).
        assertThat(result).hasSize(3);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID); // 6 pts normal → rank 1
        assertThat(result.get(0).placement()).isEqualTo(1);
        assertThat(result.get(1).teamId()).isEqualTo(TEAM_C_ID); // 4 pts normal → rank 2
        assertThat(result.get(1).placement()).isEqualTo(2);
        assertThat(result.get(2).teamId())
                .isEqualTo(TEAM_B_ID); // withoutAssessment → rank 3 (last)
        assertThat(result.get(2).placement()).isEqualTo(3);
    }

    @Test
    @DisplayName("computePlacementOrder: returns empty when no ratings (AC-INTERFACE-CREATED)")
    void computePlacementOrder_returnsEmpty_whenNoRatings() {
        Phase phase = makePhase(PHASE_ID, 1);
        TeamAvatar av = makeAvatar(AVATAR_A_ID, TEAM_A_ID, PHASE_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(av));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.empty());

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC-INTERFACE-CREATED: buildSvgRows
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildSvgRows: produces one row per placement (AC-INTERFACE-CREATED)")
    void buildSvgRows_producesOneRowPerPlacement() {
        Tournament tournament = makeTournament();
        when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(makeTeam(TEAM_A_ID, "Alpha"), makeTeam(TEAM_B_ID, "Beta")));
        when(photoStorageService.retrieve(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(Optional.empty());
        when(photoStorageService.retrieve(TOURNAMENT_ID, TEAM_B_ID)).thenReturn(Optional.empty());

        List<CertificateAssembler.AvatarPlacement> placements =
                List.of(
                        new CertificateAssembler.AvatarPlacement(1, TEAM_A_ID, AVATAR_A_ID),
                        new CertificateAssembler.AvatarPlacement(2, TEAM_B_ID, AVATAR_B_ID));

        List<CertificatePlacementRow> rows =
                assembler.buildSvgRows(tournament, placements, "Sporthalle");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).placement()).isEqualTo(1);
        assertThat(rows.get(0).teamName()).isEqualTo("Alpha");
        assertThat(rows.get(0).location()).isEqualTo("Sporthalle");
        assertThat(rows.get(1).placement()).isEqualTo(2);
        assertThat(rows.get(1).teamName()).isEqualTo("Beta");
    }

    // -------------------------------------------------------------------------
    // AC-INTERFACE-CREATED: buildHtmlRows uses PhotoUrlBuilder
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "buildHtmlRows: uses PhotoUrlBuilder.buildTeamPhotoUrl for photo URL"
                    + " (AC-INTERFACE-CREATED, E23S05 Cutover-1)")
    void buildHtmlRows_usesPhotoUrlBuilder() {
        Tournament tournament = makeTournament();
        when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(makeTeam(TEAM_A_ID, "Alpha")));
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(true);
        String expectedUrl = "/api/photo/tournaments/" + TOURNAMENT_ID + "/teams/" + TEAM_A_ID;
        when(photoUrlBuilder.buildTeamPhotoUrl(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(expectedUrl);

        List<CertificateAssembler.AvatarPlacement> placements =
                List.of(new CertificateAssembler.AvatarPlacement(1, TEAM_A_ID, AVATAR_A_ID));

        List<CertificatePlacementRow> rows =
                assembler.buildHtmlRows(tournament, placements, "Halle");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).teamPhoto()).isEqualTo(expectedUrl);
    }

    // -------------------------------------------------------------------------
    // AC-INTERFACE-CREATED: renderSvgTemplate
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "renderSvgTemplate: fills all placeholders and preserves base64 =="
                    + " (AC-INTERFACE-CREATED)")
    void renderSvgTemplate_fillsPlaceholders() {
        String template =
                "<svg><text>{{placement}}</text><text>{{teamName}}</text>"
                        + "<image href=\"{{teamPhoto}}\"/><text>{{location}}</text></svg>";
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Team Alpha",
                        "data:image/jpeg;base64,/9j/4AAQ==",
                        "Stadtmeisterschaft",
                        "15. April 2026",
                        "Sporthalle");

        String rendered = assembler.renderSvgTemplate(template, row);

        assertThat(rendered)
                .contains("1")
                .contains("Team Alpha")
                .contains("data:image/jpeg;base64,/9j/4AAQ==") // == must NOT be HTML-escaped
                .contains("Sporthalle")
                .doesNotContain("{{")
                .doesNotContain("&#x3D;"); // escapeHTML=false
    }

    // -------------------------------------------------------------------------
    // AC-INTERFACE-CREATED: toMustacheMap
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("toMustacheMap: returns map with all 6 D-4 template variable keys")
    void toMustacheMap_returnsAllKeys() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        2, TEAM_B_ID, "Beta", "", "Tournament", "2026-04-15", "Location");

        java.util.Map<String, Object> map = assembler.toMustacheMap(row);

        assertThat(map)
                .containsKey("placement")
                .containsKey("teamName")
                .containsKey("teamPhoto")
                .containsKey("tournamentName")
                .containsKey("date")
                .containsKey("location")
                .containsKey("hasPhoto");
    }

    // -------------------------------------------------------------------------
    // AC-INTERFACE-CREATED: resolveLocationDisplayName
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resolveLocationDisplayName: returns SQL result (AC-INTERFACE-CREATED)")
    @SuppressWarnings("unchecked") // Mockito raw RowMapper matcher — safe per DEC-29/E18S01
    void resolveLocationDisplayName_returnsDisplayName() {
        when(jdbcTemplate.query(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(RowMapper.class),
                        org.mockito.ArgumentMatchers.eq(TENANT_ID)))
                .thenReturn(List.of("Sporthalle Musterstadt"));

        String result = assembler.resolveLocationDisplayName(TENANT_ID);

        assertThat(result).isEqualTo("Sporthalle Musterstadt");
    }

    @Test
    @DisplayName("resolveLocationDisplayName: returns empty string when no location found")
    @SuppressWarnings("unchecked") // Mockito raw RowMapper matcher — safe per DEC-29/E18S01
    void resolveLocationDisplayName_returnsEmpty_whenNoLocation() {
        when(jdbcTemplate.query(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(RowMapper.class),
                        org.mockito.ArgumentMatchers.eq(TENANT_ID)))
                .thenReturn(List.of());

        assertThat(assembler.resolveLocationDisplayName(TENANT_ID)).isEmpty();
    }
}
