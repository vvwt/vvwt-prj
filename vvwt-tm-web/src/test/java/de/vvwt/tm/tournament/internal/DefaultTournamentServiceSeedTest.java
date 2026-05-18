// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.activity.ActivityTypeService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for auto-seed behaviour in {@link DefaultTournamentService#createTournament} (E05S12).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED (E05S12): the seed implementation did not exist at commit time.
 * The class compiled but {@code createTournament} did not yet inject {@link MessageSource} or
 * persist Team rows — causing assertion failures that prove the RED state (DEC-22 Iron Law).
 *
 * <h2>Same-package white-box access (DEC-36)</h2>
 *
 * <p>This test class resides in {@code de.vvwt.tm.tournament.internal} (same package as {@link
 * DefaultTournamentService}) — white-box access to the implementation class is permitted per
 * DEC-36.
 *
 * <h2>Coverage — E05S12 acceptance criteria</h2>
 *
 * <ul>
 *   <li>AC-IMPL-AUTO-SEED-AT-CREATE — N team rows persisted per createTournament call
 *   <li>AC-IMPL-TEAMNUMBER-PLACEHOLDER — team numbers 1..N, each unique
 *   <li>AC-IMPL-DESC-FORMAT — description = String.format("%s %02d", label, n)
 *   <li>AC-IMPL-DEFAULT-FLAGS — participate=true, refereeAssignment=true, withoutAssessment=false
 *       (E05S13: refereeAssignment flipped false→true for auto-seeded teams; participate and
 *       withoutAssessment remain unchanged per E05S12 AC-IMPL-DEFAULT-FLAGS other clauses)
 *   <li>AC-I18N-LABEL-FROM-MESSAGE-BUNDLE — label sourced from team.defaultLabel key
 *   <li>AC-I18N-LOCALE-CHAIN — tenant.language ?? "de" fallback chain (today's state)
 *   <li>AC-ERR-ATOMIC-ROLLBACK — team-insert failure rolls back tournament row too
 * </ul>
 *
 * @see DefaultTournamentService
 * @see de.vvwt.tm.tournament.TournamentService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first, this test was the RED state)</a>
 * @see <a href="DEC-36">DEC-36 — Same-package tests may white-box reference impl class</a>
 * @see <a href="E05S12">E05S12 — Auto-seed N empty team slots at tournament creation</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultTournamentService — auto-seed unit tests (E05S12)")
class DefaultTournamentServiceSeedTest {

    @Mock private TournamentRepository tournamentRepository;

    @Mock private MatchGeneratorRegistry matchGeneratorRegistry;

    @Mock private JdbcTemplate jdbcTemplate;

    @Mock private MessageSource messageSource;

    @Mock private TeamRepository teamRepository;

    @Mock private ActivityTypeService activityTypeService;

    private DefaultTournamentService service;

    private static final UUID DEFAULT_LOCATION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Stub JdbcTemplate.query for resolveDefaultLocationId() — returns one location UUID
        org.mockito.Mockito.lenient()
                .when(
                        jdbcTemplate.query(
                                org.mockito.ArgumentMatchers.contains("locations"),
                                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of(DEFAULT_LOCATION_ID));

        // Stub JdbcTemplate.queryForList for resolveTenantLanguage() — returns "de"
        org.mockito.Mockito.lenient()
                .when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("de"));

        // Stub MessageSource.getMessage for team.defaultLabel → "Mannschaft"
        org.mockito.Mockito.lenient()
                .when(
                        messageSource.getMessage(
                                eq("team.defaultLabel"),
                                isNull(),
                                eq("Mannschaft"),
                                any(Locale.class)))
                .thenReturn("Mannschaft");

        // Stub MessageSource.getMessage for tom.label.team_photo → "Mannschaftsfoto"
        // (E53S05: seeding call — seedMannschaftsfoto=null tests pass null → seeding is triggered)
        org.mockito.Mockito.lenient()
                .when(
                        messageSource.getMessage(
                                eq("tom.label.team_photo"), isNull(), any(Locale.class)))
                .thenReturn("Mannschaftsfoto");

        // Stub activityTypeService.create() — returns null (return value not used in unit tests)
        org.mockito.Mockito.lenient()
                .when(activityTypeService.create(any(), anyString(), anyString(), any(), eq(1)))
                .thenReturn(null);

        service =
                new DefaultTournamentService(
                        tournamentRepository,
                        matchGeneratorRegistry,
                        jdbcTemplate,
                        messageSource,
                        teamRepository,
                        activityTypeService);
    }

    // =========================================================================
    // AC-IMPL-AUTO-SEED-AT-CREATE — N team rows persisted
    // =========================================================================

    @Test
    @DisplayName(
            "createTournament() seeds exactly N=teamCount Team rows (AC-IMPL-AUTO-SEED-AT-CREATE)")
    void createTournament_seedsExactlyNTeamRows() {
        int teamCount = 4;
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "Seed Test Tournament",
                null,
                teamCount,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null → seeding (lenient stub)

        ArgumentCaptor<Team> teamCaptor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository, org.mockito.Mockito.times(teamCount)).save(teamCaptor.capture());

        assertThat(teamCaptor.getAllValues()).hasSize(teamCount);
    }

    // =========================================================================
    // AC-IMPL-TEAMNUMBER-PLACEHOLDER — team numbers 1..N
    // =========================================================================

    @Test
    @DisplayName(
            "createTournament() assigns teamNumbers 1..N sequentially"
                    + " (AC-IMPL-TEAMNUMBER-PLACEHOLDER)")
    void createTournament_teamNumbers_are1ToN() {
        int teamCount = 3;
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "Number Test",
                null,
                teamCount,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null

        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository, org.mockito.Mockito.times(teamCount)).save(captor.capture());

        List<Integer> teamNumbers =
                captor.getAllValues().stream().map(Team::getTeamNumber).sorted().toList();
        assertThat(teamNumbers).containsExactly(1, 2, 3);
    }

    // =========================================================================
    // AC-IMPL-DESC-FORMAT — description = String.format("%s %02d", label, n)
    // =========================================================================

    @Test
    @DisplayName("createTournament() sets description = '{label} 01' format (AC-IMPL-DESC-FORMAT)")
    void createTournament_descriptionFormat_matchesMannschaft() {
        int teamCount = 2;
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "Format Test",
                null,
                teamCount,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null

        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository, org.mockito.Mockito.times(teamCount)).save(captor.capture());

        List<String> descriptions =
                captor.getAllValues().stream()
                        .sorted(java.util.Comparator.comparingInt(Team::getTeamNumber))
                        .map(Team::getDescription)
                        .toList();
        assertThat(descriptions).containsExactly("Mannschaft 01", "Mannschaft 02");
    }

    @Test
    @DisplayName("createTournament() description for N=100 is '{label} 100' (no width clamp)")
    void createTournament_descriptionFormat_noWidthClampAt100() {
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "Large Count",
                null,
                100,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null

        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository, org.mockito.Mockito.times(100)).save(captor.capture());

        // Last team (team number 100) must have "Mannschaft 100" (not "Mannschaft 00")
        Team lastTeam =
                captor.getAllValues().stream()
                        .max(java.util.Comparator.comparingInt(Team::getTeamNumber))
                        .orElseThrow();
        assertThat(lastTeam.getDescription()).isEqualTo("Mannschaft 100");
    }

    // =========================================================================
    // AC-IMPL-DEFAULT-FLAGS
    // =========================================================================

    @Test
    @DisplayName(
            "createTournament() seeds teams with participate=true, refereeAssignment=true,"
                    + " withoutAssessment=false (E05S13: refereeAssignment flipped false→true)")
    void createTournament_defaultFlags_participateTrue_refFalse_assessFalse() {
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "Flags Test",
                null,
                2,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null

        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        for (Team team : captor.getAllValues()) {
            assertThat(team.isParticipate()).as("participate must be true").isTrue();
            assertThat(team.isRefereeAssignment())
                    .as(
                            "refereeAssignment must be true (E05S13: Mannschaft=Schiedsgericht"
                                    + " default)")
                    .isTrue();
            assertThat(team.isWithoutAssessment()).as("withoutAssessment must be false").isFalse();
        }
    }

    // =========================================================================
    // AC-I18N-LABEL-FROM-MESSAGE-BUNDLE — MessageSource resolved
    // =========================================================================

    @Test
    @DisplayName("createTournament() resolves team.defaultLabel from MessageSource")
    void createTournament_labelFromMessageBundle() {
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "i18n Test",
                null,
                2,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null

        verify(messageSource, atLeastOnce())
                .getMessage(eq("team.defaultLabel"), isNull(), eq("Mannschaft"), any(Locale.class));
    }

    // =========================================================================
    // AC-I18N-LOCALE-CHAIN — tenant.language ?? "de"
    // =========================================================================

    @Test
    @DisplayName(
            "createTournament() uses tenant.language='de' and resolves Locale.GERMAN"
                    + " (AC-I18N-LOCALE-CHAIN)")
    void createTournament_localeChain_deFromTenant_resolvesGermanLocale() {
        // tenant language = "de" (already stubbed in setUp)
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "Locale Test",
                null,
                2,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null

        // Verify MessageSource was called with a German locale
        ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);
        verify(messageSource, atLeastOnce())
                .getMessage(
                        eq("team.defaultLabel"),
                        isNull(),
                        eq("Mannschaft"),
                        localeCaptor.capture());
        assertThat(localeCaptor.getValue().getLanguage())
                .as("locale must be 'de' when tenant language is 'de'")
                .isEqualTo("de");
    }

    @Test
    @DisplayName(
            "createTournament() falls back to 'de' locale when tenant language is null"
                    + " (AC-I18N-LOCALE-CHAIN)")
    void createTournament_localeChain_fallsBackToDeWhenTenantLanguageNull() {
        // Override: queryForList returns null tenant language
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(new ArrayList<>(Arrays.asList((String) null)));
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "Null-Language Tenant",
                null,
                2,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null

        ArgumentCaptor<Locale> localeCaptor = ArgumentCaptor.forClass(Locale.class);
        verify(messageSource, atLeastOnce())
                .getMessage(
                        eq("team.defaultLabel"),
                        isNull(),
                        eq("Mannschaft"),
                        localeCaptor.capture());
        assertThat(localeCaptor.getValue().getLanguage())
                .as("locale must fall back to 'de' when tenant language is null")
                .isEqualTo("de");
    }

    // =========================================================================
    // AC-ERR-ATOMIC-ROLLBACK — rollback on team insert failure
    // =========================================================================

    @Test
    @DisplayName(
            "createTournament() propagates DataAccessException from teamRepository.save"
                    + " (AC-ERR-ATOMIC-ROLLBACK)")
    void createTournament_propagatesDataAccessException_onTeamInsertFailure() {
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("simulated constraint violation"))
                .when(teamRepository)
                .save(any());

        assertThatThrownBy(
                        () ->
                                service.createTournament(
                                        "Rollback Test",
                                        null,
                                        2,
                                        2,
                                        "BEST_OF_3",
                                        "setPoints",
                                        "standardVolleyball",
                                        "roundRobin",
                                        null,
                                        null,
                                        null, // E53S05: seedMannschaftsfoto = null
                                        null)) // E68S01: organizer = null
                .isInstanceOf(DataAccessException.class);
    }

    // =========================================================================
    // AC-IMPL-TEAMNUMBER-PLACEHOLDER — tournament_id set on each team
    // =========================================================================

    @Test
    @DisplayName("createTournament() sets tournamentId on each seeded Team")
    void createTournament_setsCorrectTournamentIdOnTeams() {
        when(tournamentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTournament(
                "TournamentId Test",
                null,
                3,
                2,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                null,
                null,
                null, // E53S05: seedMannschaftsfoto = null
                null); // E68S01: organizer = null

        ArgumentCaptor<Tournament> tCaptor = ArgumentCaptor.forClass(Tournament.class);
        verify(tournamentRepository).save(tCaptor.capture());
        UUID expectedTournamentId = tCaptor.getValue().getId();

        ArgumentCaptor<Team> teamCaptor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository, org.mockito.Mockito.times(3)).save(teamCaptor.capture());

        for (Team team : teamCaptor.getAllValues()) {
            assertThat(team.getTournamentId())
                    .as("each team's tournamentId must match the saved tournament's id")
                    .isEqualTo(expectedTournamentId);
            assertThat(team.getId()).as("each team must have a non-null UUID").isNotNull();
        }
    }
}
