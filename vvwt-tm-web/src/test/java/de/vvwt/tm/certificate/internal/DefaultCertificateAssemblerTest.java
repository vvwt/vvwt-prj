// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.certificate.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.certificate.CertificateAssembler;
import de.vvwt.tm.certificate.CertificatePlacementRow;
import de.vvwt.tm.certificate.LocaleResolver;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

/**
 * Q-1a TDD RED-first test suite for the rebuilt {@link DefaultCertificateAssembler} (E36S05, DEC-22
 * Iron Law).
 *
 * <p>Authored RED-first against the absent impl after the legacy Q-1b {@code
 * DefaultCertificateAssembler} was deleted at commit {@code c40db76}. These tests first failed at
 * compile time (RED state per AC-TDD-RED-FIRST-EVIDENCE model (a)).
 *
 * <p><b>E46S03 extension:</b> constructor expanded to 8-arg (added {@link MessageSource} + {@link
 * LocaleResolver}); existing CertificatePlacementRow constructor calls updated to 8-component;
 * tests for the new buildMustacheMap behavior (13 tom_-prefixed keys + labels + organizer +
 * locale-aware date) added under RED-first discipline. WHITE-BOX tests for the renamed {@code
 * tom_has_photo} key added.
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
 * @since E36S05 (updated E46S03)
 */
@DisplayName("DefaultCertificateAssembler — E36S05 Q-1a TDD RED-first rebuild (E46S03 extension)")
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
    private MessageSource messageSource;
    private LocaleResolver localeResolver;

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
     * Creates a TeamAvatar in group 1 at the given groupPosition (E12S09 — DEC-9 structural
     * identity for Siegerehrung placement tests).
     *
     * @param avatarId the avatar UUID
     * @param teamId the assigned team UUID (non-null = team assigned)
     * @param groupPosition 1-based position within the single group (DEC-9)
     */
    private static TeamAvatar makeAvatarAt(UUID avatarId, UUID teamId, int groupPosition) {
        return new TeamAvatar(
                avatarId, TOURNAMENT_ID, PHASE_ID, 1, groupPosition, teamId, null, null);
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

    /**
     * Makes a tournament with organizer "Volleyball-Verein Musterstadt" and appointment 2026-04-15.
     */
    private static Tournament makeTournament() {
        Tournament t =
                new Tournament(
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
        t.setOrganizer("Volleyball-Verein Musterstadt");
        return t;
    }

    @BeforeEach
    void setUp() {
        phaseRepository = mock(PhaseRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        teamAvatarRatingRepository = mock(TeamAvatarRatingRepository.class);
        teamRepository = mock(TeamRepository.class);
        photoStorageService = mock(PhotoStorageService.class);
        photoUrlBuilder = mock(PhotoUrlBuilder.class);
        messageSource = mock(MessageSource.class);
        localeResolver = mock(LocaleResolver.class);

        // Stub messageSource for standard German labels (default stubs for most tests)
        stubGermanLabels();

        // Default locale: GERMAN
        when(localeResolver.resolveForTeam(any(UUID.class), any(UUID.class)))
                .thenReturn(Locale.GERMAN);
        when(localeResolver.resolveForTournament(any(UUID.class))).thenReturn(Locale.GERMAN);

        // White-box constructor call (same package — DEC-36 same-package exception).
        // 8-arg signature: original 6 + MessageSource + LocaleResolver (E46S03).
        assembler =
                new DefaultCertificateAssembler(
                        phaseRepository,
                        teamAvatarRepository,
                        teamAvatarRatingRepository,
                        teamRepository,
                        photoStorageService,
                        photoUrlBuilder,
                        messageSource,
                        localeResolver);
    }

    private void stubGermanLabels() {
        when(messageSource.getMessage(eq("tom.label.certificate"), isNull(), any(Locale.class)))
                .thenReturn("URKUNDE");
        when(messageSource.getMessage(eq("tom.label.place"), isNull(), any(Locale.class)))
                .thenReturn("PLATZ");
        when(messageSource.getMessage(eq("tom.label.achieved_by"), isNull(), any(Locale.class)))
                .thenReturn("erreicht von");
        when(messageSource.getMessage(eq("tom.label.team_photo"), isNull(), any(Locale.class)))
                .thenReturn("Mannschaftsfoto");
        when(messageSource.getMessage(eq("tom.label.generated_by"), isNull(), any(Locale.class)))
                .thenReturn("generated by");
        when(messageSource.getMessage(eq("tom.label.on"), isNull(), any(Locale.class)))
                .thenReturn("am");
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
    @DisplayName(
            "computePlacementOrder: returns empty when avatars have no assigned team and no ratings"
                    + " (genuinely not-ready — AC4 E12S09)")
    void computePlacementOrder_returnsEmpty_whenNoAssignedTeamAndNoRatings() {
        // Simulate a phase where team-assignment has not happened yet: teamId == null
        Phase phase = makePhase(1);
        TeamAvatar av = makeAvatar(AVATAR_A_ID, null); // null teamId = unassigned
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(av));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.empty());

        assertThat(assembler.computePlacementOrder(TOURNAMENT_ID, phase)).isEmpty();
    }

    // =========================================================================
    // E12S09: Siegerehrung-phase placement from group positions (AC1/AC2 RED-first)
    // =========================================================================

    /**
     * AC1 RED reproduction (DEC-22 Iron Law): given a Siegerehrung-final-phase tournament whose
     * avatars have assigned teams and group positions but ZERO ratings, the pre-fix code returns an
     * empty placement list (false 400). This test documents the RED state and MUST fail against the
     * pre-fix code.
     *
     * <p>AC2 expected behaviour (post-fix): computePlacementOrder uses the Siegerehrung phase's
     * TeamAvatar groupPositions — groupPosition 1..N maps directly to places 1..N (single group,
     * DEC-9). The result is non-empty and correctly ordered.
     */
    @Test
    @DisplayName(
            "computePlacementOrder: Siegerehrung phase — group positions used as placement"
                    + " when no ratings exist (AC1 RED/AC2 GREEN — E12S09 DEC-9)")
    void computePlacementOrder_siegerehrung_usesGroupPositionWhenNoRatings() {
        // Three avatars, all with assigned teams, groupPositions 1, 2, 3 — no ratings
        Phase phase = makePhase(3); // Phase 3 = Siegerehrung (highest sequenceNumber)
        TeamAvatar avA = makeAvatarAt(AVATAR_A_ID, TEAM_A_ID, 1); // place 1
        TeamAvatar avB = makeAvatarAt(AVATAR_B_ID, TEAM_B_ID, 2); // place 2
        TeamAvatar avC = makeAvatarAt(AVATAR_C_ID, TEAM_C_ID, 3); // place 3
        // Deliberately shuffled — placement must be ordered by groupPosition, not input order
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avC, avA, avB));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.findById(AVATAR_B_ID)).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.findById(AVATAR_C_ID)).thenReturn(Optional.empty());

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        // AC2: non-empty, ordered by groupPosition ascending (1→2→3 = places 1→2→3)
        assertThat(result).hasSize(3);
        assertThat(result.get(0).placement()).isEqualTo(1);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID);
        assertThat(result.get(1).placement()).isEqualTo(2);
        assertThat(result.get(1).teamId()).isEqualTo(TEAM_B_ID);
        assertThat(result.get(2).placement()).isEqualTo(3);
        assertThat(result.get(2).teamId()).isEqualTo(TEAM_C_ID);
    }

    @Test
    @DisplayName(
            "computePlacementOrder: Siegerehrung phase — single team, placement = 1"
                    + " (AC2 single-team edge case — E12S09)")
    void computePlacementOrder_siegerehrung_singleTeam_placementOne() {
        Phase phase = makePhase(3);
        TeamAvatar avA = makeAvatarAt(AVATAR_A_ID, TEAM_A_ID, 1);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avA));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.empty());

        List<CertificateAssembler.AvatarPlacement> result =
                assembler.computePlacementOrder(TOURNAMENT_ID, phase);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).placement()).isEqualTo(1);
        assertThat(result.get(0).teamId()).isEqualTo(TEAM_A_ID);
    }

    @Test
    @DisplayName(
            "computePlacementOrder: Siegerehrung phase — avatars with no assigned team return empty"
                    + " (AC4 error-handling preserved — E12S09)")
    void computePlacementOrder_siegerehrung_noAssignedTeams_returnsEmpty() {
        // Phase exists with avatars but no teams assigned — genuinely not ready
        Phase phase = makePhase(3);
        TeamAvatar avA = makeAvatarAt(AVATAR_A_ID, null, 1); // null teamId = unassigned
        TeamAvatar avB = makeAvatarAt(AVATAR_B_ID, null, 2);
        when(teamAvatarRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(avA, avB));
        when(teamAvatarRatingRepository.findById(AVATAR_A_ID)).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.findById(AVATAR_B_ID)).thenReturn(Optional.empty());

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
    @DisplayName("renderSvgTemplate: fills tom_-prefixed template variable placeholders (E46S03)")
    void renderSvgTemplate_fillsAllPlaceholders() {
        String template =
                "<svg><text>{{tom_placement}}</text><text>{{tom_team_name}}</text>"
                        + "<image href=\"{{tom_team_photo}}\"/><text>{{tom_tournament_name}}</text>"
                        + "<text>{{tom_date}}</text><text>{{tom_location}}</text>"
                        + "<text>{{tom_organizer}}</text></svg>";
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Team Alpha",
                        "data:image/png;base64,abc==",
                        "Stadtmeisterschaft 2026",
                        "15. April 2026",
                        "Sporthalle Musterstadt",
                        "Volleyball-Verein Musterstadt");

        String rendered = assembler.renderSvgTemplate(template, row);

        assertThat(rendered)
                .contains("1")
                .contains("Team Alpha")
                .contains("data:image/png;base64,abc==")
                .contains("Stadtmeisterschaft 2026")
                .contains("15. April 2026")
                .contains("Sporthalle Musterstadt")
                .contains("Volleyball-Verein Musterstadt")
                .doesNotContain("{{");
    }

    @Test
    @DisplayName("renderSvgTemplate: preserves base64 == — escapeHTML=false mandatory (E12S01 AC6)")
    void renderSvgTemplate_preservesBase64Equals_escapeHtmlFalse() {
        String template = "<image href=\"{{tom_team_photo}}\"/>";
        String dataUri = "data:image/jpeg;base64,/9j/4AAQSkZJRgAB==";
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Team",
                        dataUri,
                        "Tournament",
                        "2026-04-15",
                        "Location",
                        "Organizer");

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
        String template = "<image href=\"{{tom_team_photo}}\"/>";
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1, TEAM_A_ID, "Team", "", "Tournament", "date", "location", "Organizer");

        String rendered = assembler.renderSvgTemplate(template, row);

        assertThat(rendered)
                .as("Missing teamPhoto renders as empty string (lenient mode)")
                .contains("href=\"\"")
                .doesNotContain("{{tom_team_photo}}");
    }

    // =========================================================================
    // 6. toMustacheMap — 13 tom_-prefixed keys (E46S03 AC-MAP-CONTAINS-13-PREFIXED-KEYS)
    // =========================================================================

    @Test
    @DisplayName("toMustacheMap: returns map with all 13 tom_-prefixed keys (E46S03 D-17 + D-16)")
    void toMustacheMap_returnsAllThirteenPrefixedKeys() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        2,
                        TEAM_B_ID,
                        "Beta",
                        "",
                        "Tournament",
                        "2026-04-15",
                        "Location",
                        "Organizer Club");

        Map<String, Object> map = assembler.toMustacheMap(row);

        // AC-MAP-CONTAINS-13-PREFIXED-KEYS: all 13 tom_-prefixed keys present
        assertThat(map)
                .containsKey("tom_placement")
                .containsKey("tom_team_name")
                .containsKey("tom_team_photo")
                .containsKey("tom_tournament_name")
                .containsKey("tom_date")
                .containsKey("tom_location")
                .containsKey("tom_organizer")
                .containsKey("tom_label_certificate")
                .containsKey("tom_label_place")
                .containsKey("tom_label_achieved_by")
                .containsKey("tom_label_team_photo")
                .containsKey("tom_label_generated_by")
                .containsKey("tom_label_on");

        // AC-MAP-NO-UNPREFIXED-KEYS: legacy keys absent
        assertThat(map)
                .doesNotContainKey("placement")
                .doesNotContainKey("teamName")
                .doesNotContainKey("teamPhoto")
                .doesNotContainKey("tournamentName")
                .doesNotContainKey("date")
                .doesNotContainKey("location");
    }

    @Test
    @DisplayName(
            "toMustacheMap: tom_has_photo convenience key present and false when photo empty"
                    + " (E46S03 AC-CONVENIENCE-KEY-DECISION-RECORDED)")
    void toMustacheMap_tomHasPhotoFalse_whenEmptyPhoto() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Alpha",
                        "",
                        "Tournament",
                        "2026-04-15",
                        "Location",
                        "Organizer");

        Map<String, Object> map = assembler.toMustacheMap(row);

        assertThat(map.get("tom_has_photo")).isEqualTo(false);
    }

    @Test
    @DisplayName(
            "toMustacheMap: tom_has_photo convenience key is true when photo non-empty (E46S03)")
    void toMustacheMap_tomHasPhotoTrue_whenNonEmptyPhoto() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Alpha",
                        "data:image/jpeg;base64,abc",
                        "Tournament",
                        "2026-04-15",
                        "Location",
                        "Organizer");

        Map<String, Object> map = assembler.toMustacheMap(row);

        assertThat(map.get("tom_has_photo")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "toMustacheMap: tom_organizer is empty string when organizer is null"
                    + " (E46S03 AC-ORGANIZER-IN-MAP null handling)")
    void toMustacheMap_organizerIsEmptyString_whenNull() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1, TEAM_A_ID, "Alpha", "", "Tournament", "2026-04-15", "Location", null);

        Map<String, Object> map = assembler.toMustacheMap(row);

        assertThat(map.get("tom_organizer")).isEqualTo("");
    }

    @Test
    @DisplayName(
            "toMustacheMap: 6 label keys resolved via MessageSource"
                    + " (E46S03 AC-LABELS-RESOLVED-AT-RENDER-TIME)")
    void toMustacheMap_labelKeysResolvedViaMessageSource() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Alpha",
                        "",
                        "Tournament",
                        "2026-04-15",
                        "Location",
                        "Organizer");

        Map<String, Object> map = assembler.toMustacheMap(row);

        assertThat(map.get("tom_label_certificate")).isEqualTo("URKUNDE");
        assertThat(map.get("tom_label_place")).isEqualTo("PLATZ");
        assertThat(map.get("tom_label_achieved_by")).isEqualTo("erreicht von");
        assertThat(map.get("tom_label_team_photo")).isEqualTo("Mannschaftsfoto");
        assertThat(map.get("tom_label_generated_by")).isEqualTo("generated by");
        assertThat(map.get("tom_label_on")).isEqualTo("am");
    }

    // =========================================================================
    // E46S03: AC-CUSTOM-UPLOAD-LEGACY-RENDERS-EMPTY + AC-NO-BACKWARD-COMPAT-LAYER
    // =========================================================================

    @Test
    @DisplayName(
            "renderSvgTemplate: legacy unprefixed {{placement}} renders as empty string"
                    + " (E46S03 AC-CUSTOM-UPLOAD-LEGACY-RENDERS-EMPTY, H-2 codified residual risk)")
    void renderSvgTemplate_legacyUnprefixedVariable_rendersEmpty() {
        // A legacy custom-upload template that uses the OLD unprefixed variable name
        String legacyTemplate = "<svg><text>{{placement}}</text></svg>";
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Alpha",
                        "",
                        "Tournament",
                        "2026-04-15",
                        "Sporthalle",
                        "Organizer");

        String rendered = assembler.renderSvgTemplate(legacyTemplate, row);

        // defaultValue("") means missing keys render as empty string — not "null", not the key name
        assertThat(rendered)
                .as(
                        "Legacy {{placement}} must render as empty string (D-17 hard-cut"
                                + " + H-2 accepted residual risk)")
                .contains("<text></text>")
                .doesNotContain("{{placement}}")
                .doesNotContain("placement"); // The VALUE is empty — the key name does not appear
    }

    @Test
    @DisplayName(
            "toMustacheMap: all keys start with tom_ — no backward-compat aliases"
                    + " (E46S03 AC-NO-BACKWARD-COMPAT-LAYER)")
    void toMustacheMap_allKeysPrefixedWithTom_noBackwardCompatAliases() {
        CertificatePlacementRow row =
                new CertificatePlacementRow(
                        1,
                        TEAM_A_ID,
                        "Alpha",
                        "photo",
                        "Tournament",
                        "2026-04-15",
                        "Location",
                        "Organizer");

        Map<String, Object> map = assembler.toMustacheMap(row);

        // Every key in the map must start with "tom_"
        assertThat(map.keySet())
                .allSatisfy(
                        key ->
                                assertThat(key)
                                        .as("key '%s' must start with 'tom_'", key)
                                        .startsWith("tom_"));
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
