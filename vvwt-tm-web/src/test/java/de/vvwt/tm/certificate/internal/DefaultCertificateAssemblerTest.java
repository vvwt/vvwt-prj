package de.vvwt.tm.certificate.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.certificate.CertificateAssembler;
import de.vvwt.tm.certificate.CertificatePlacementRow;
import de.vvwt.tm.photo.PhotoFileMetadata;
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
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Q-1a TDD RED-first test suite for the rebuilt {@link DefaultCertificateAssembler} (E36S05, DEC-22
 * Iron Law).
 *
 * <p>Authored RED-first against the absent impl after the legacy Q-1b {@code
 * DefaultCertificateAssembler} was deleted at commit {@code c40db76}. These tests first failed at
 * compile time (RED state per AC-TDD-RED-FIRST-EVIDENCE model (a)).
 *
 * <p>Per DEC-36: this test class is in {@code de.vvwt.tm.certificate.internal} — the SAME package
 * as {@code DefaultCertificateAssembler}. White-box access (package-private helpers, constructor
 * direct call) is permitted. The primary {@code assembler} field is declared as the {@link
 * CertificateAssembler} PUBLIC INTERFACE (best practice, same-package rule allows white-box
 * constructor call).
 *
 * <p>Per DEC-41 §3 hierarchy clause (1): these are the MANDATORY new TDD tests for the new code.
 * The preserved {@code CertificateAssemblerTest} is SUPPLEMENTARY per clause (2) only.
 *
 * <p>Per AC-AUDIT-II-GAP-DOCUMENTED: audit (ii) did NOT enumerate {@code
 * de.vvwt.tm.certificate.CertificateAssemblerTest}. This test class supplements it; the
 * interface-level test is preserved on the basis of DEC-36, DEC-22 Q-1a Javadoc evidence, and
 * DEC-41 §1(d) Spec-Anchored criterion observed in source.
 *
 * @see DefaultCertificateAssembler
 * @see CertificateAssembler
 * @since E36S05
 */
@DisplayName("DefaultCertificateAssembler — E36S05 Q-1a TDD RED-first rebuild")
class DefaultCertificateAssemblerTest {

    // -------------------------------------------------------------------------
    // Shared test IDs
    // -------------------------------------------------------------------------

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
    // Mocks and subject
    // -------------------------------------------------------------------------

    private PhaseRepository phaseRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private TeamAvatarRatingRepository teamAvatarRatingRepository;
    private TeamRepository teamRepository;
    private PhotoStorageService photoStorageService;
    private PhotoUrlBuilder photoUrlBuilder;

    /**
     * DEC-36: declared as public interface (best practice even in same-package context). White-box
     * constructor call below is permitted (same package per DEC-36).
     */
    private CertificateAssembler assembler;

    // -------------------------------------------------------------------------
    // Test data builders
    // -------------------------------------------------------------------------

    private static Phase makePhase(int sequenceNumber) {
        return new Phase(
                PHASE_ID,
                TOURNAMENT_ID,
                sequenceNumber,
                "Phase " + sequenceNumber,
                "COMPLETED",
                3,
                LocalDateTime.now());
    }

    private static TeamAvatar makeAvatar(UUID avatarId, UUID teamId) {
        return new TeamAvatar(avatarId, TOURNAMENT_ID, PHASE_ID, 1, 1, teamId, null, null);
    }

    /**
     * Builds a TeamAvatarRating with the given points and quotients for DEC-33 sort testing.
     *
     * @param avatarId the avatar UUID (also the rating PK)
     * @param points match points
     * @param setQuotient sets won / sets played ratio
     * @param ballQuotient balls won / balls played ratio
     * @param withoutAssessment true → team ranks last per DEC-33 regardless of numeric scores
     */
    private static TeamAvatarRating makeRating(
            UUID avatarId,
            int points,
            double setQuotient,
            double ballQuotient,
            boolean withoutAssessment) {
        return new TeamAvatarRating(
                avatarId,
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
        t.setTournamentId(TOURNAMENT_ID);
        t.setDescription(description);
        t.setTeamNumber(1);
        return t;
    }

    private static Tournament makeTournament() {
        return new Tournament(
                TOURNAMENT_ID,
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
        photoUrlBuilder = mock(PhotoUrlBuilder.class);

        // White-box constructor call (same package — DEC-36 same-package exception).
        // 6-arg signature preserved verbatim per AC-CONSTRUCTOR-INJECTION-PRESERVED.
        assembler =
                new DefaultCertificateAssembler(
                        phaseRepository,
                        teamAvatarRepository,
                        teamAvatarRatingRepository,
                        teamRepository,
                        photoStorageService,
                        photoUrlBuilder);
    }

    // =========================================================================
    // 1. getFinalPhase — AC-INTERFACE-CONTRACT-PRESERVED
    // =========================================================================

    @Test
    @DisplayName("getFinalPhase: returns phase with the highest sequenceNumber")
    void getFinalPhase_returnsHighestSequencePhase() {
        Phase p1 = makePhase(1);
        UUID p2Id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        Phase p2 = new Phase(p2Id, TOURNAMENT_ID, 2, "Final", "COMPLETED", 0, null);
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

    // =========================================================================
    // 2. computePlacementOrder — DEC-33 sort order (AC-DEC41-FRESH-RED-FIRST-TESTS)
    // =========================================================================

    @Test
    @DisplayName("computePlacementOrder: points DESC — higher points ranks first")
    void computePlacementOrder_pointsDescending() {
        Phase phase = makePhase(1);
        TeamAvatar avA = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        TeamAvatar avB = makeAvatar(AVATAR_B_ID, TEAM_B_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avA, avB));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID))
                .thenReturn(Optional.of(makeRating(AVATAR_A_ID, 6, 2.0, 1.5, false)));
        when(teamAvatarRatingRepository.findById(AVATAR_B_ID))
                .thenReturn(Optional.of(makeRating(AVATAR_B_ID, 4, 2.0, 1.5, false)));

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID);
        assertThat(result.get(0).placement()).isEqualTo(1);
        assertThat(result.get(1).teamId()).isEqualTo(TEAM_B_ID);
        assertThat(result.get(1).placement()).isEqualTo(2);
    }

    @Test
    @DisplayName("computePlacementOrder: setQuotient DESC breaks tie on equal points (DEC-33)")
    void computePlacementOrder_setQuotientTieBreak() {
        Phase phase = makePhase(1);
        TeamAvatar avA = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        TeamAvatar avB = makeAvatar(AVATAR_B_ID, TEAM_B_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avA, avB));
        // Same points, B has higher setQuotient → B ranks first
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID))
                .thenReturn(Optional.of(makeRating(AVATAR_A_ID, 4, 1.0, 1.0, false)));
        when(teamAvatarRatingRepository.findById(AVATAR_B_ID))
                .thenReturn(Optional.of(makeRating(AVATAR_B_ID, 4, 2.0, 1.0, false)));

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_B_ID); // higher setQuotient → rank 1
        assertThat(result.get(1).teamId()).isEqualTo(TEAM_A_ID);
    }

    @Test
    @DisplayName(
            "computePlacementOrder: isWithoutAssessment teams rank LAST regardless of points"
                    + " (DEC-33)")
    void computePlacementOrder_withoutAssessmentRanksLast() {
        Phase phase = makePhase(1);
        TeamAvatar avA = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        TeamAvatar avB = makeAvatar(AVATAR_B_ID, TEAM_B_ID);
        TeamAvatar avC = makeAvatar(AVATAR_C_ID, TEAM_C_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avA, avB, avC));
        // B has 10 points but withoutAssessment=true → must be last
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID))
                .thenReturn(Optional.of(makeRating(AVATAR_A_ID, 6, 2.0, 1.5, false)));
        when(teamAvatarRatingRepository.findById(AVATAR_B_ID))
                .thenReturn(Optional.of(makeRating(AVATAR_B_ID, 10, 5.0, 5.0, true)));
        when(teamAvatarRatingRepository.findById(AVATAR_C_ID))
                .thenReturn(Optional.of(makeRating(AVATAR_C_ID, 4, 1.5, 1.2, false)));

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID); // 6 pts normal → 1
        assertThat(result.get(1).teamId()).isEqualTo(TEAM_C_ID); // 4 pts normal → 2
        assertThat(result.get(2).teamId()).isEqualTo(TEAM_B_ID); // withoutAssessment → 3 (last)
    }

    @Test
    @DisplayName("computePlacementOrder: returns empty when no ratings exist (no matches played)")
    void computePlacementOrder_returnsEmpty_whenNoRatings() {
        Phase phase = makePhase(1);
        TeamAvatar av = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(av));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.empty());

        assertThat(assembler.computePlacementOrder(TOURNAMENT_ID, phase)).isEmpty();
    }

    @Test
    @DisplayName("computePlacementOrder: returns empty when phase has no avatars")
    void computePlacementOrder_returnsEmpty_whenNoAvatars() {
        Phase phase = makePhase(1);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of());

        assertThat(assembler.computePlacementOrder(TOURNAMENT_ID, phase)).isEmpty();
    }

    @Test
    @DisplayName("computePlacementOrder: single team gets placement = 1")
    void computePlacementOrder_singleTeam_placementOne() {
        Phase phase = makePhase(1);
        TeamAvatar av = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(av));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID))
                .thenReturn(Optional.of(makeRating(AVATAR_A_ID, 6, 1.0, 1.0, false)));

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).placement()).isEqualTo(1);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID);
    }

    // =========================================================================
    // 3. buildSvgRows — SVG path: base64 data URI (AC-INTERFACE-CONTRACT-PRESERVED)
    // =========================================================================

    @Test
    @DisplayName("buildSvgRows: produces one row per placement with correct teamName and location")
    void buildSvgRows_oneRowPerPlacement() {
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

    @Test
    @DisplayName("buildSvgRows: teamPhoto is base64 data URI when photo exists (E12S06 AC6)")
    void buildSvgRows_teamPhotoIsBase64DataUri_whenPhotoExists() throws Exception {
        Tournament tournament = makeTournament();
        when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(makeTeam(TEAM_A_ID, "Alpha")));
        byte[] fakeBytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        PhotoStorageService.PhotoResult photoResult =
                new PhotoStorageService.PhotoResult(
                        new ByteArrayInputStream(fakeBytes),
                        "image/jpeg",
                        new PhotoFileMetadata("photo.jpg", fakeBytes.length, Instant.now()));
        when(photoStorageService.retrieve(TOURNAMENT_ID, TEAM_A_ID))
                .thenReturn(Optional.of(photoResult));

        List<CertificatePlacementRow> rows =
                assembler.buildSvgRows(
                        tournament,
                        List.of(
                                new CertificateAssembler.AvatarPlacement(
                                        1, TEAM_A_ID, AVATAR_A_ID)),
                        "Halle");

        assertThat(rows).hasSize(1);
        String expectedBase64 = Base64.getEncoder().encodeToString(fakeBytes);
        assertThat(rows.get(0).teamPhoto()).isEqualTo("data:image/jpeg;base64," + expectedBase64);
    }

    @Test
    @DisplayName("buildSvgRows: teamPhoto is empty string when no photo exists (E12S06 AC6)")
    void buildSvgRows_teamPhotoIsEmpty_whenNoPhoto() {
        Tournament tournament = makeTournament();
        when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(makeTeam(TEAM_A_ID, "Alpha")));
        when(photoStorageService.retrieve(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(Optional.empty());

        List<CertificatePlacementRow> rows =
                assembler.buildSvgRows(
                        tournament,
                        List.of(
                                new CertificateAssembler.AvatarPlacement(
                                        1, TEAM_A_ID, AVATAR_A_ID)),
                        "Halle");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).teamPhoto()).isEmpty();
    }

    // =========================================================================
    // 4. buildHtmlRows — HTML path: relative URL via PhotoUrlBuilder
    // =========================================================================

    @Test
    @DisplayName("buildHtmlRows: teamPhoto uses PhotoUrlBuilder URL when photo exists")
    void buildHtmlRows_teamPhotoIsUrl_whenPhotoExists() {
        Tournament tournament = makeTournament();
        when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(makeTeam(TEAM_A_ID, "Alpha")));
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(true);
        String expectedUrl = "/api/photo/tournaments/" + TOURNAMENT_ID + "/teams/" + TEAM_A_ID;
        when(photoUrlBuilder.buildTeamPhotoUrl(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(expectedUrl);

        List<CertificatePlacementRow> rows =
                assembler.buildHtmlRows(
                        tournament,
                        List.of(
                                new CertificateAssembler.AvatarPlacement(
                                        1, TEAM_A_ID, AVATAR_A_ID)),
                        "Halle");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).teamPhoto()).isEqualTo(expectedUrl);
    }

    @Test
    @DisplayName("buildHtmlRows: teamPhoto is empty string when no photo exists")
    void buildHtmlRows_teamPhotoIsEmpty_whenNoPhoto() {
        Tournament tournament = makeTournament();
        when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(makeTeam(TEAM_A_ID, "Alpha")));
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(false);

        List<CertificatePlacementRow> rows =
                assembler.buildHtmlRows(
                        tournament,
                        List.of(
                                new CertificateAssembler.AvatarPlacement(
                                        1, TEAM_A_ID, AVATAR_A_ID)),
                        "Halle");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).teamPhoto()).isEmpty();
    }

    @Test
    @DisplayName("buildHtmlRows: produces one row per placement with correct fields")
    void buildHtmlRows_oneRowPerPlacement() {
        Tournament tournament = makeTournament();
        when(teamRepository.findByTournamentId(TOURNAMENT_ID))
                .thenReturn(List.of(makeTeam(TEAM_A_ID, "Alpha"), makeTeam(TEAM_B_ID, "Beta")));
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(false);
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_B_ID)).thenReturn(false);

        List<CertificatePlacementRow> rows =
                assembler.buildHtmlRows(
                        tournament,
                        List.of(
                                new CertificateAssembler.AvatarPlacement(1, TEAM_A_ID, AVATAR_A_ID),
                                new CertificateAssembler.AvatarPlacement(
                                        2, TEAM_B_ID, AVATAR_B_ID)),
                        "Sporthalle");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).placement()).isEqualTo(1);
        assertThat(rows.get(0).teamName()).isEqualTo("Alpha");
        assertThat(rows.get(1).placement()).isEqualTo(2);
        assertThat(rows.get(1).teamName()).isEqualTo("Beta");
    }

    // =========================================================================
    // 5. renderSvgTemplate — Mustache (E12S01 AC6)
    // =========================================================================

    @Test
    @DisplayName("renderSvgTemplate: fills all 6 template variable placeholders")
    void renderSvgTemplate_fillsAllPlaceholders() {
        String template =
                "<svg><text>{{placement}}</text><text>{{teamName}}</text>"
                        + "<image href=\"{{teamPhoto}}\"/><text>{{tournamentName}}</text>"
                        + "<text>{{date}}</text><text>{{location}}</text></svg>";
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Team Alpha",
                        "data:image/png;base64,abc==",
                        "Stadtmeisterschaft 2026",
                        "15. April 2026",
                        "Sporthalle Musterstadt");

        String rendered = assembler.renderSvgTemplate(template, row);

        assertThat(rendered)
                .contains("1")
                .contains("Team Alpha")
                .contains("data:image/png;base64,abc==")
                .contains("Stadtmeisterschaft 2026")
                .contains("15. April 2026")
                .contains("Sporthalle Musterstadt")
                .doesNotContain("{{");
    }

    @Test
    @DisplayName("renderSvgTemplate: preserves base64 == — escapeHTML=false mandatory (E12S01 AC6)")
    void renderSvgTemplate_preservesBase64Equals_escapeHtmlFalse() {
        String template = "<image href=\"{{teamPhoto}}\"/>";
        String dataUri = "data:image/jpeg;base64,/9j/4AAQSkZJRgAB==";
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1, TEAM_A_ID, "Team", dataUri, "Tournament", "2026-04-15", "Location");

        String rendered = assembler.renderSvgTemplate(template, row);

        assertThat(rendered)
                .as(
                        "base64 '==' must not be HTML-escaped (escapeHTML=false mandatory per"
                                + " E12S01 AC6)")
                .contains("data:image/jpeg;base64,/9j/4AAQSkZJRgAB==")
                .doesNotContain("&#x3D;");
    }

    @Test
    @DisplayName(
            "renderSvgTemplate: renders empty string for missing teamPhoto (defaultValue=''"
                    + " lenient)")
    void renderSvgTemplate_emptyStringForMissingPhoto() {
        String template = "<image href=\"{{teamPhoto}}\"/>";
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1, TEAM_A_ID, "Team", "", "Tournament", "date", "location");

        String rendered = assembler.renderSvgTemplate(template, row);

        assertThat(rendered)
                .as("Missing teamPhoto renders as empty string (lenient mode)")
                .contains("href=\"\"")
                .doesNotContain("{{teamPhoto}}");
    }

    // =========================================================================
    // 6. toMustacheMap — all 7 keys present (AC-INTERFACE-CONTRACT-PRESERVED)
    // =========================================================================

    @Test
    @DisplayName("toMustacheMap: returns map with all 7 required keys including hasPhoto")
    void toMustacheMap_returnsAllSevenKeys() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        2, TEAM_B_ID, "Beta", "", "Tournament", "2026-04-15", "Location");

        Map<String, Object> map = assembler.toMustacheMap(row);

        assertThat(map)
                .containsKey("placement")
                .containsKey("teamName")
                .containsKey("teamPhoto")
                .containsKey("tournamentName")
                .containsKey("date")
                .containsKey("location")
                .containsKey("hasPhoto");
    }

    @Test
    @DisplayName("toMustacheMap: hasPhoto is false when teamPhoto is empty")
    void toMustacheMap_hasPhotoFalse_whenEmptyPhoto() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1, TEAM_A_ID, "Alpha", "", "Tournament", "2026-04-15", "Location");

        Map<String, Object> map = assembler.toMustacheMap(row);

        assertThat(map.get("hasPhoto")).isEqualTo(false);
    }

    @Test
    @DisplayName("toMustacheMap: hasPhoto is true when teamPhoto is non-empty")
    void toMustacheMap_hasPhotoTrue_whenNonEmptyPhoto() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Alpha",
                        "data:image/jpeg;base64,abc",
                        "Tournament",
                        "2026-04-15",
                        "Location");

        Map<String, Object> map = assembler.toMustacheMap(row);

        assertThat(map.get("hasPhoto")).isEqualTo(true);
    }

    // =========================================================================
    // 7. White-box: fetchPhotoAsBase64DataUri (package-private helper — DEC-36 same-package)
    // =========================================================================

    @Test
    @DisplayName("fetchPhotoAsBase64DataUri: returns data URI when photo exists")
    void fetchPhotoAsBase64DataUri_returnsDataUri_whenPhotoExists() throws Exception {
        byte[] fakeBytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        PhotoStorageService.PhotoResult photoResult =
                new PhotoStorageService.PhotoResult(
                        new ByteArrayInputStream(fakeBytes),
                        "image/jpeg",
                        new PhotoFileMetadata("photo.jpg", fakeBytes.length, Instant.now()));
        when(photoStorageService.retrieve(TOURNAMENT_ID, TEAM_A_ID))
                .thenReturn(Optional.of(photoResult));

        String result =
                ((DefaultCertificateAssembler) assembler)
                        .fetchPhotoAsBase64DataUri(TOURNAMENT_ID, TEAM_A_ID);

        String expectedBase64 = Base64.getEncoder().encodeToString(fakeBytes);
        assertThat(result).isEqualTo("data:image/jpeg;base64," + expectedBase64);
    }

    @Test
    @DisplayName("fetchPhotoAsBase64DataUri: returns empty string when no photo")
    void fetchPhotoAsBase64DataUri_returnsEmpty_whenNoPhoto() {
        when(photoStorageService.retrieve(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(Optional.empty());

        String result =
                ((DefaultCertificateAssembler) assembler)
                        .fetchPhotoAsBase64DataUri(TOURNAMENT_ID, TEAM_A_ID);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // 8. White-box: buildPhotoUrl (package-private helper — DEC-36 same-package)
    // =========================================================================

    @Test
    @DisplayName("buildPhotoUrl: returns API URL from PhotoUrlBuilder when photo exists")
    void buildPhotoUrl_returnsUrl_whenPhotoExists() {
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(true);
        String expectedUrl = "/api/photo/tournaments/" + TOURNAMENT_ID + "/teams/" + TEAM_A_ID;
        when(photoUrlBuilder.buildTeamPhotoUrl(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(expectedUrl);

        String result =
                ((DefaultCertificateAssembler) assembler).buildPhotoUrl(TOURNAMENT_ID, TEAM_A_ID);

        assertThat(result).isEqualTo(expectedUrl);
    }

    @Test
    @DisplayName("buildPhotoUrl: returns empty string when no photo exists")
    void buildPhotoUrl_returnsEmpty_whenNoPhoto() {
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(false);

        String result =
                ((DefaultCertificateAssembler) assembler).buildPhotoUrl(TOURNAMENT_ID, TEAM_A_ID);

        assertThat(result).isEmpty();
    }
}
