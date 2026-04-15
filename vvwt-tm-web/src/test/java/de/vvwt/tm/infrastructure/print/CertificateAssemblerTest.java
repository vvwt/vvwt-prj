package de.vvwt.tm.infrastructure.print;

import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.TeamAvatarRating;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.photo.PhotoStorageService;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CertificateAssembler} — E12S06.
 *
 * <p>Covers placement calculation (AC5, D-33), photo embedding (AC6 SVG and HTML paths),
 * Mustache rendering (AC1, AC9 error mode), and location lookup.
 * All dependencies are mocked — no Spring context required.
 *
 * @see CertificateAssembler
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S06.story.md">Story E12S06</a>
 */
@DisplayName("CertificateAssembler — E12S06 unit tests")
class CertificateAssemblerTest {

    // -------------------------------------------------------------------------
    // Shared test IDs
    // -------------------------------------------------------------------------

    private static final UUID TENANT_ID     = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOURNAMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PHASE_ID      = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TEAM_A_ID     = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID TEAM_B_ID     = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID TEAM_C_ID     = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID AVATAR_A_ID   = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID AVATAR_B_ID   = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID AVATAR_C_ID   = UUID.fromString("00000000-0000-0000-0000-000000000009");

    // -------------------------------------------------------------------------
    // Mocks
    // -------------------------------------------------------------------------

    private PhaseRepository phaseRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private TeamAvatarRatingRepository teamAvatarRatingRepository;
    private TeamRepository teamRepository;
    private PhotoStorageService photoStorageService;
    private JdbcTemplate jdbcTemplate;

    private CertificateAssembler assembler;

    // -------------------------------------------------------------------------
    // Test data builders
    // -------------------------------------------------------------------------

    private static Phase makePhase(int sequenceNumber) {
        return new Phase(PHASE_ID, TENANT_ID, TOURNAMENT_ID, sequenceNumber,
                "Final Phase", "COMPLETED", 3, LocalDateTime.now());
    }

    private static TeamAvatar makeAvatar(UUID avatarId, UUID teamId) {
        return new TeamAvatar(avatarId, TENANT_ID, TOURNAMENT_ID, PHASE_ID,
                1, 1, teamId, null, null);
    }

    /**
     * Builds a TeamAvatarRating with the given points and quotients for sorting tests.
     */
    private static TeamAvatarRating makeRating(UUID avatarId, int points,
                                               double setQuotient, double ballQuotient,
                                               boolean withoutAssessment) {
        return new TeamAvatarRating(avatarId, TENANT_ID,
                3, 6, points, 3, 3, 60, 30,
                setQuotient, ballQuotient, withoutAssessment, null);
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
        return new Tournament(TOURNAMENT_ID, TENANT_ID, "Stadtmeisterschaft 2026",
                "BEST_OF_3", "setPoints", "standardVolleyball", "roundRobin",
                "COMPLETED", null, LocalDateTime.of(2026, 4, 15, 9, 0), 4, 8);
    }

    @BeforeEach
    void setUp() {
        phaseRepository = mock(PhaseRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        teamAvatarRatingRepository = mock(TeamAvatarRatingRepository.class);
        teamRepository = mock(TeamRepository.class);
        photoStorageService = mock(PhotoStorageService.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        assembler = new CertificateAssembler(
                phaseRepository,
                teamAvatarRepository,
                teamAvatarRatingRepository,
                teamRepository,
                photoStorageService,
                jdbcTemplate);
    }

    // -------------------------------------------------------------------------
    // AC5: getFinalPhase
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC5: getFinalPhase returns the phase with the highest sequenceNumber")
    void getFinalPhase_returnsHighestSequencePhase() {
        Phase p1 = makePhase(1);
        UUID p2Id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        Phase p2 = new Phase(p2Id, TENANT_ID, TOURNAMENT_ID, 2, "Final", "COMPLETED", 0, null);
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(p1, p2));

        Optional<Phase> result = assembler.getFinalPhase(TOURNAMENT_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getSequenceNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC5: getFinalPhase returns empty when tournament has no phases (AC8 precondition)")
    void getFinalPhase_returnsEmpty_whenNoPhases() {
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());

        assertThat(assembler.getFinalPhase(TOURNAMENT_ID)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC5: computePlacementOrder — D-33 sort
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC5: placement order follows D-33 (points DESC → setQuotient DESC → ballQuotient DESC)")
    void computePlacementOrder_respectsD33SortOrder() {
        Phase phase = makePhase(1);

        TeamAvatar avA = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        TeamAvatar avB = makeAvatar(AVATAR_B_ID, TEAM_B_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avA, avB));

        // Team A: 6 points. Team B: 4 points. A ranks first.
        TeamAvatarRating ratingA = makeRating(AVATAR_A_ID, 6, 2.0, 1.5, false);
        TeamAvatarRating ratingB = makeRating(AVATAR_B_ID, 4, 1.5, 1.2, false);
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.of(ratingA));
        when(teamAvatarRatingRepository.findById(AVATAR_B_ID)).thenReturn(Optional.of(ratingB));

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).placement()).isEqualTo(1);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID);
        assertThat(result.get(1).placement()).isEqualTo(2);
        assertThat(result.get(1).teamId()).isEqualTo(TEAM_B_ID);
    }

    @Test
    @DisplayName("AC5: isWithoutAssessment teams are placed last (D-26)")
    void computePlacementOrder_withoutAssessmentTeamPlacedLast() {
        Phase phase = makePhase(1);

        TeamAvatar avA = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        TeamAvatar avB = makeAvatar(AVATAR_B_ID, TEAM_B_ID);
        TeamAvatar avC = makeAvatar(AVATAR_C_ID, TEAM_C_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avA, avB, avC));

        // Team A: normal, 6 points. Team B: withoutAssessment (should be last even with more points).
        // Team C: normal, 4 points.
        TeamAvatarRating ratingA = makeRating(AVATAR_A_ID, 6, 2.0, 1.5, false);
        TeamAvatarRating ratingB = makeRating(AVATAR_B_ID, 10, 5.0, 5.0, true);  // withoutAssessment
        TeamAvatarRating ratingC = makeRating(AVATAR_C_ID, 4, 1.5, 1.2, false);
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.of(ratingA));
        when(teamAvatarRatingRepository.findById(AVATAR_B_ID)).thenReturn(Optional.of(ratingB));
        when(teamAvatarRatingRepository.findById(AVATAR_C_ID)).thenReturn(Optional.of(ratingC));

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).hasSize(3);
        // A (6 pts, normal) → placement 1
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID);
        // C (4 pts, normal) → placement 2
        assertThat(result.get(1).teamId()).isEqualTo(TEAM_C_ID);
        // B (withoutAssessment) → placement 3 (last)
        assertThat(result.get(2).teamId()).isEqualTo(TEAM_B_ID);
    }

    @Test
    @DisplayName("AC5/AC8: computePlacementOrder returns empty when no ratings exist (no matches played)")
    void computePlacementOrder_returnsEmpty_whenNoRatings() {
        Phase phase = makePhase(1);
        TeamAvatar av = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(av));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.empty());

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AC5: single team gets placement = 1")
    void computePlacementOrder_singleTeam_getsPlacementOne() {
        Phase phase = makePhase(1);
        TeamAvatar av = makeAvatar(AVATAR_A_ID, TEAM_A_ID);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(av));

        TeamAvatarRating rating = makeRating(AVATAR_A_ID, 6, 1.0, 1.0, false);
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.of(rating));

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).placement()).isEqualTo(1);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID);
    }

    // -------------------------------------------------------------------------
    // AC6: photo embedding — SVG path (base64 data URI)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC6 (SVG): fetchPhotoAsBase64DataUri returns data URI when photo exists")
    void fetchPhotoAsBase64DataUri_returnDataUri_whenPhotoExists() throws Exception {
        byte[] fakePhotoBytes = {(byte)0xFF, (byte)0xD8, (byte)0xFF}; // minimal JPEG signature
        PhotoStorageService.PhotoResult photoResult = new PhotoStorageService.PhotoResult(
                new ByteArrayInputStream(fakePhotoBytes), "image/jpeg",
                new de.vvwt.tm.domain.photo.PhotoFileMetadata(
                        "photo.jpg", fakePhotoBytes.length, java.time.Instant.now()));
        when(photoStorageService.retrieve(TOURNAMENT_ID, TEAM_A_ID))
                .thenReturn(Optional.of(photoResult));

        String result = assembler.fetchPhotoAsBase64DataUri(TOURNAMENT_ID, TEAM_A_ID);

        assertThat(result).startsWith("data:image/jpeg;base64,");
        String expectedBase64 = Base64.getEncoder().encodeToString(fakePhotoBytes);
        assertThat(result).endsWith(expectedBase64);
    }

    @Test
    @DisplayName("AC6 (SVG): fetchPhotoAsBase64DataUri returns empty string when no photo exists")
    void fetchPhotoAsBase64DataUri_returnsEmptyString_whenNoPhoto() {
        when(photoStorageService.retrieve(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(Optional.empty());

        String result = assembler.fetchPhotoAsBase64DataUri(TOURNAMENT_ID, TEAM_A_ID);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC6: photo embedding — HTML path (URL)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC6 (HTML): buildPhotoUrl returns API URL when photo exists")
    void buildPhotoUrl_returnsApiUrl_whenPhotoExists() {
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(true);

        String result = assembler.buildPhotoUrl(TOURNAMENT_ID, TEAM_A_ID);

        assertThat(result).isEqualTo(
                "/api/tournaments/" + TOURNAMENT_ID + "/teams/" + TEAM_A_ID + "/photo");
    }

    @Test
    @DisplayName("AC6 (HTML): buildPhotoUrl returns empty string when no photo")
    void buildPhotoUrl_returnsEmptyString_whenNoPhoto() {
        when(photoStorageService.hasPhoto(TOURNAMENT_ID, TEAM_A_ID)).thenReturn(false);

        assertThat(assembler.buildPhotoUrl(TOURNAMENT_ID, TEAM_A_ID)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC1: renderSvgTemplate — Mustache rendering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC1: renderSvgTemplate fills all 6 D-4 placeholders")
    void renderSvgTemplate_fillsAllPlaceholders() {
        String svgTemplate = "<svg><text>{{placement}}</text><text>{{teamName}}</text>" +
                "<image href=\"{{teamPhoto}}\"/>" +
                "<text>{{tournamentName}}</text><text>{{date}}</text><text>{{location}}</text></svg>";

        CertificatePlacementRow row = new CertificatePlacementRow(
                1, TEAM_A_ID, "Team Alpha", "data:image/png;base64,abc==",
                "Stadtmeisterschaft 2026", "15. April 2026", "Sporthalle Musterstadt");

        String rendered = assembler.renderSvgTemplate(svgTemplate, row);

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
    @DisplayName("AC1/E12S01-AC6: renderSvgTemplate preserves base64 == (escapeHTML=false)")
    void renderSvgTemplate_preservesBase64Equals_escapeHtmlFalse() {
        // The "==" at the end of base64 strings must not be HTML-escaped to "&#x3D;&#x3D;"
        String svgTemplate = "<image href=\"{{teamPhoto}}\"/>";
        String dataUri = "data:image/jpeg;base64,/9j/4AAQSkZJRgAB==";

        CertificatePlacementRow row = new CertificatePlacementRow(
                1, TEAM_A_ID, "Team", dataUri,
                "Tournament", "2026-04-15", "Location");

        String rendered = assembler.renderSvgTemplate(svgTemplate, row);

        assertThat(rendered)
                .as("base64 '==' must not be HTML-escaped (escapeHTML=false mandatory per E12S01 AC6)")
                .contains("data:image/jpeg;base64,/9j/4AAQSkZJRgAB==")
                .doesNotContain("&#x3D;");
    }

    @Test
    @DisplayName("AC1: renderSvgTemplate renders empty string for missing teamPhoto (lenient mode)")
    void renderSvgTemplate_rendersEmptyForMissingPhoto() {
        String svgTemplate = "<image href=\"{{teamPhoto}}\"/>";

        CertificatePlacementRow row = new CertificatePlacementRow(
                1, TEAM_A_ID, "Team", "",  // empty photo
                "Tournament", "date", "location");

        String rendered = assembler.renderSvgTemplate(svgTemplate, row);

        assertThat(rendered)
                .as("Missing teamPhoto renders as empty string (lenient mode)")
                .contains("href=\"\"")
                .doesNotContain("{{teamPhoto}}");
    }

    // -------------------------------------------------------------------------
    // Location lookup
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resolveLocationDisplayName returns display name from locations table")
    void resolveLocationDisplayName_returnsDisplayName() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(TENANT_ID)))
                .thenReturn(List.of("Sporthalle Musterstadt"));

        String result = assembler.resolveLocationDisplayName(TENANT_ID);

        assertThat(result).isEqualTo("Sporthalle Musterstadt");
    }

    @Test
    @DisplayName("resolveLocationDisplayName returns empty string when no location found")
    void resolveLocationDisplayName_returnsEmptyString_whenNoLocation() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(TENANT_ID)))
                .thenReturn(List.of());

        assertThat(assembler.resolveLocationDisplayName(TENANT_ID)).isEmpty();
    }
}
