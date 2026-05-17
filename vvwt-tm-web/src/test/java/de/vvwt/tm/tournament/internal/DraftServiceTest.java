// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.TimelineEntry;
import de.vvwt.tm.tournament.TimelineEntryType;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentLifecycleService;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftBreak;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.exceptions.TournamentNotInDraftException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit test for {@link DefaultDraftService} (AC-TDD-DraftService, E48S22 update).
 *
 * <p>Tests {@code preview(DraftConfig, int, int, LocalTime)} pure computation and {@code
 * apply(UUID, DraftConfig)} atomic six-step phase-creation delegation. Mocks Phase-aggregate
 * collaborators from E21S03, TournamentRepository for the DRAFT-precondition findByIdForUpdate
 * (DEC-37 Clause B, E48S22), ObjectMapper for draft_json serialization (E48S22), and
 * TournamentLifecycleService for DRAFT→PLANNED delegation (E48S22, DEC-35).
 *
 * <p>The DraftAlreadyAppliedException-based idempotency test (AC-DRAFT-APPLY-IDEMPOTENCY) is
 * migrated to AC-DRAFT-APPLY-NON-DRAFT-STATUS-409 semantics per E48S22
 * AC-ERROR-HANDLING-DRAFT-ALREADY-APPLIED-COLLAPSED.
 *
 * <p>Inventory: E21S01 line 171.
 *
 * @see DefaultDraftService
 * @see PhaseRepository
 * @see PhaseBreakRepository
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-37">DEC-37 — Clause B: findByIdForUpdate first-read</a>
 * @see <a href="DEC-35">DEC-35 — authority-locality: lifecycle delegation</a>
 * @see <a href="E48S17">E48S17 — AC-IMPL-APPLY-NO-PHASE-1-TEAMAVATARS</a>
 * @see <a href="E48S22">E48S22 — Atomic apply() + DRAFT-precondition + DraftAlreadyApplied
 *     collapse</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E21S03">E21S03 — Phase aggregate (mocked collaborator)</a>
 */
@ExtendWith(MockitoExtension.class)
class DraftServiceTest {

    @Mock private PhaseRepository phaseRepository;
    @Mock private PhaseBreakRepository phaseBreakRepository;
    @Mock private TimelineCalculationService timelineCalculationService;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentLifecycleService lifecycleService;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamRepository teamRepository;

    @Mock
    private JdbcTemplate
            jdbcTemplate; // E51S02: needed for DELETE-and-recreate in persistStructuralAvatars

    // E55S06 Option C: ApplicationEventPublisher removed from DefaultDraftService
    // (step-d3 event publication replaced by DraftApplicationOrchestrator)

    /**
     * E58S01 AC6: MatchGeneratorRegistry mock for registry-membership validation in saveDraft() and
     * apply(). Stubbed to return all known IDs in tests that invoke those paths.
     */
    @Mock private de.vvwt.tm.tournament.MatchGeneratorRegistry matchGeneratorRegistry;

    /**
     * E58S02 AC4/AC6: Team2AvatarDistributorRegistry mock for registry dispatch and membership
     * validation in persistStructuralAvatars(), saveDraft(), and apply(). Stubbed to return known
     * keys in setUp().
     */
    @Mock private de.vvwt.tm.tournament.Team2AvatarDistributorRegistry distributorRegistry;

    /**
     * E58S03 AC6: TeamSortCalculatorRegistry mock for sortType membership validation in saveDraft()
     * and apply(). Stubbed to return known keys in setUp().
     */
    @Mock private de.vvwt.tm.tournament.TeamSortCalculatorRegistry sortCalculatorRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DefaultDraftService draftService;

    @BeforeEach
    void setUp() {
        // Construct manually: DefaultDraftService no longer supports @InjectMocks cleanly.
        // E51S02: jdbcTemplate mock required because persistStructuralAvatars() calls
        // jdbcTemplate.update("DELETE FROM team_avatar WHERE phase_id = ?") for idempotency
        // before avatar saves — the mock silently accepts and returns 0 rows deleted.
        // E51S02: teamAvatarRepository + teamRepository added for structural avatar persistence.
        draftService =
                // E55S06 Option C: ApplicationEventPublisher removed from DefaultDraftService
                // constructor (step-d3 JDBC inserts + event moved to DraftApplicationOrchestrator)
                new DefaultDraftService(
                        phaseRepository,
                        phaseBreakRepository,
                        tournamentRepository,
                        objectMapper,
                        timelineCalculationService,
                        jdbcTemplate, // E51S02: mocked — DELETE-before-insert in
                        // persistStructuralAvatars
                        lifecycleService,
                        teamAvatarRepository,
                        teamRepository,
                        matchGeneratorRegistry, // E58S01 AC6: registry-membership validation
                        distributorRegistry, // E58S02 AC4/AC6: distributor registry dispatch
                        sortCalculatorRegistry); // E58S03 AC6: sortType membership validation
        // Stub: registry knows "roundRobin" and "awardCeremony" — used by saveDraft()/apply()
        // paths.
        // lenient() because preview_* tests do not invoke knownIds() and would trigger
        // UnnecessaryStubbingException with strict Mockito mode.
        lenient()
                .when(matchGeneratorRegistry.knownIds())
                .thenReturn(Set.of("roundRobin", "awardCeremony"));
        // E58S02: distributorRegistry knows "sequential" and "round_robin"
        lenient()
                .when(distributorRegistry.knownKeys())
                .thenReturn(Set.of("sequential", "round_robin"));
        // E58S03 AC6: sortCalculatorRegistry knows "team_number", "placement_group",
        // "group_placement"
        lenient()
                .when(sortCalculatorRegistry.knownKeys())
                .thenReturn(Set.of("team_number", "placement_group", "group_placement"));
        // E58S02: stub distributor.get("sequential") for apply() tests that use
        // persistStructuralAvatars
        de.vvwt.tm.tournament.Team2AvatarDistributor sequentialDistributor =
                org.mockito.Mockito.mock(de.vvwt.tm.tournament.Team2AvatarDistributor.class);
        lenient()
                .when(sequentialDistributor.distribute(any(), anyInt()))
                .thenAnswer(
                        invocation -> {
                            java.util.List<de.vvwt.tm.tournament.Team> teams =
                                    invocation.getArgument(0);
                            int groupCount = invocation.getArgument(1);
                            // Simple sequential logic for test stubs
                            java.util.List<de.vvwt.tm.tournament.Team2AvatarSlot> slots =
                                    new java.util.ArrayList<>();
                            int n = teams.size();
                            int ppg = (n + groupCount - 1) / groupCount;
                            if (ppg == 0) ppg = 1;
                            for (int i = 0; i < n; i++) {
                                slots.add(
                                        new de.vvwt.tm.tournament.Team2AvatarSlot(
                                                (i / ppg) + 1, (i % ppg) + 1));
                            }
                            return slots;
                        });
        lenient().when(distributorRegistry.get(eq("sequential"))).thenReturn(sequentialDistributor);
    }

    /**
     * Simple section with roundrobin gameMode. Use as a non-last section or when testing behaviour
     * that fires before the last-phase invariant check (e.g. DraftAlreadyApplied).
     */
    private static DraftSection simpleSection(int sectionNumber) {
        return new DraftSection(
                sectionNumber, "team_number", 1, "roundRobin", 0, 0, 15, 1, List.of());
    }

    /**
     * Simple section with siegerehrung gameMode. Use as the last section to satisfy the D-10
     * invariant (AC-IMPL-LAST-PHASE-INVARIANT, E48S01).
     */
    private static DraftSection lastSection(int sectionNumber) {
        return new DraftSection(
                sectionNumber, "team_number", 1, "awardCeremony", 0, 0, 15, 1, List.of());
    }

    private static DraftSection sectionWithBreak(int sectionNumber) {
        DraftBreak breakItem = new DraftBreak(1, 10, "Pause");
        return new DraftSection(
                sectionNumber, "team_number", 1, "awardCeremony", 0, 0, 15, 1, List.of(breakItem));
    }

    // -------------------------------------------------------------------------
    // preview() — pure computation (field-aware lap formula,
    // AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED)
    // -------------------------------------------------------------------------

    /**
     * AC-TDD-DraftService / AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED Scenario A: 6 teams in 1 group,
     * fieldCount=3, RR → totalLaps=5.
     *
     * <p>Formula: teamConflict=floor(6/2)*1=3, eff=min(3,3)=3, matches=15, laps=ceil(15/3)=5.
     *
     * <p>RED-first: written before preview(DraftConfig, int, int) exists (E48S10).
     */
    @Test
    void preview_scenarioA_6teams1group_field3_returns5Laps() {
        // 6 teams / 1 group: section with groupCount=1
        DraftSection section =
                new DraftSection(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 6, 3, null);

        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(5);
        assertThat(result.sections().get(0).getTotalMatches()).isEqualTo(15);
    }

    /**
     * AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED Scenario B: 12 teams in 2 groups of 6, fieldCount=3 →
     * totalLaps=10 (user's bug example).
     *
     * <p>Formula: teamConflict=floor(6/2)*2=6, eff=min(6,3)=3, matches=30, laps=ceil(30/3)=10.
     *
     * <p>Current bug yields 5 (teamsPerGroup-1). RED-first (E48S10).
     */
    @Test
    void preview_scenarioB_12teams2groups_field3_returns10Laps() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 12, 3, null);

        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(10);
        assertThat(result.sections().get(0).getTotalMatches()).isEqualTo(30);
    }

    /**
     * AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED Scenario C: 12 teams in 3 groups of 4, fieldCount=3 →
     * totalLaps=6.
     *
     * <p>Formula: teamConflict=floor(4/2)*3=6, eff=min(6,3)=3, matches=18, laps=ceil(18/3)=6.
     *
     * <p>Current bug yields 3 (teamsPerGroup-1). RED-first (E48S10).
     */
    @Test
    void preview_scenarioC_12teams3groups_field3_returns6Laps() {
        DraftSection section =
                new DraftSection(1, "team_number", 3, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 12, 3, null);

        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(6);
        assertThat(result.sections().get(0).getTotalMatches()).isEqualTo(18);
    }

    /**
     * AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED Scenario D: 12 teams in 4 groups of 3, fieldCount=3 →
     * totalLaps=4.
     *
     * <p>Formula: teamConflict=floor(3/2)*4=4, eff=min(4,3)=3, matches=12, laps=ceil(12/3)=4.
     */
    @Test
    void preview_scenarioD_12teams4groups_field3_returns4Laps() {
        DraftSection section =
                new DraftSection(1, "team_number", 4, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 12, 3, null);

        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(4);
        assertThat(result.sections().get(0).getTotalMatches()).isEqualTo(12);
    }

    /**
     * AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED Scenario E (covers F-1 finding): 12 teams in 4 groups
     * of 3, fieldCount=6 → totalLaps=3 (team-conflict-bound active).
     *
     * <p>Formula: teamConflict=floor(3/2)*4=4, eff=min(4,6)=4, matches=12, laps=ceil(12/4)=3.
     */
    @Test
    void preview_scenarioE_12teams4groups_field6_returns3Laps() {
        DraftSection section =
                new DraftSection(1, "team_number", 4, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 12, 6, null);

        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(3);
    }

    /**
     * AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED Scenario F: 6 teams in 1 group, fieldCount=10 →
     * totalLaps=5 (RR-bound, fields excess).
     *
     * <p>Formula: teamConflict=floor(6/2)*1=3, eff=min(3,10)=3, matches=15, laps=ceil(15/3)=5.
     */
    @Test
    void preview_scenarioF_6teams1group_field10_returns5Laps() {
        DraftSection section =
                new DraftSection(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 6, 10, null);

        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(5);
    }

    /**
     * AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED Scenario G: 8 teams in 2 groups of 4, fieldCount=1 →
     * totalLaps=12.
     *
     * <p>Formula: teamConflict=floor(4/2)*2=4, eff=min(4,1)=1, matches=12, laps=ceil(12/1)=12.
     */
    @Test
    void preview_scenarioG_8teams2groups_field1_returns12Laps() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 8, 1, null);

        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(12);
        assertThat(result.sections().get(0).getTotalMatches()).isEqualTo(12);
    }

    /**
     * AC-TEST-COMPUTE-PREVIEW-FIELD-AWARE-RED Scenario H: totalMatches=0 (teamsPerGroup<=1) →
     * totalLaps=0. No division by zero.
     *
     * <p>Formula: teamsPerGroup=1 → matchesPerGroup=0, totalMatches=0, totalLaps=0. Also covers
     * AC-ERROR-HANDLING-FIELDCOUNT-CLAMP for fieldCount=0 input → max(1, min(0, fieldCount)) =
     * max(1, 0) = 1, then totalLaps=0 (zero matches).
     */
    @Test
    void preview_scenarioH_totalMatchesZero_returns0Laps() {
        // 1 team per group → matchesPerGroup=0 → totalMatches=0 → totalLaps=0
        DraftSection section =
                new DraftSection(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        // 1 participating team total, 1 group → teamsPerGroup=1 → 0 matches
        DraftPreviewResult result = draftService.preview(config, 1, 3, null);

        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(0);
        assertThat(result.sections().get(0).getTotalMatches()).isEqualTo(0);
    }

    /**
     * AC-ERROR-HANDLING-FIELDCOUNT-CLAMP: fieldCount=0 clamped to 1 via max(1,...). For 4 teams/1
     * group: totalMatches=6, effectivePerLap=max(1,min(2,0))=1, laps=6.
     */
    @Test
    void preview_fieldCountZero_clampedTo1() {
        DraftSection section =
                new DraftSection(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 4, 0, null);

        // effectivePerLap = max(1, min(2, 0)) = 1, totalMatches=6, laps=ceil(6/1)=6
        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(6);
    }

    /**
     * Refactored existing test (Q-1b under DEC-22 §refactor-clause): Updated to 3-arg API. 4 teams
     * / 1 group, fieldCount=10 (excess) → laps=3, matches=6.
     *
     * <p>Formula: teamConflict=floor(4/2)*1=2, eff=min(2,10)=2, matches=6, laps=ceil(6/2)=3.
     *
     * <p>This is the refactored form of the pre-existing {@code
     * preview_withOneSection_returnsOnePreviewSection} test — the original 2-arg API is removed per
     * AC-IMPL-DRAFT-SERVICE-SIGNATURE; this test updates the call site atomically alongside the
     * production change.
     */
    @Test
    void preview_withOneSection_returnsOnePreviewSection() {
        DraftConfig config = new DraftConfig(List.of(simpleSection(1)));
        int participatingTeamCount = 4;

        // 4-arg API (E48S12): plannedStartTime=null → empty timeline
        DraftPreviewResult result = draftService.preview(config, participatingTeamCount, 10, null);

        assertThat(result.sections()).hasSize(1);
        // 4 teams / 1 group = 4 teams per group → teamConflict=2, eff=2, laps=3, matches=6
        assertThat(result.sections().get(0).getTotalLaps()).isEqualTo(3);
        assertThat(result.sections().get(0).getTotalMatches()).isEqualTo(6);
        assertThat(result.timeline()).isEmpty();
    }

    /** Refactored existing test (Q-1b): preview with empty config returns empty sections. */
    @Test
    void preview_withEmptyConfig_returnsEmptySections() {
        DraftConfig config = DraftConfig.empty();

        DraftPreviewResult result = draftService.preview(config, 4, 3, null);

        assertThat(result.sections()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // apply() — phase creation
    // -------------------------------------------------------------------------

    /** AC-TDD-DraftService: apply with one section creates one Phase. */
    @Test
    void apply_withOneSection_createsOnePhase() {
        UUID tournamentId = UUID.randomUUID();
        // Last (and only) section must be siegerehrung per D-10 invariant (E48S01)
        DraftConfig config = new DraftConfig(List.of(lastSection(1)));

        // DEC-37 Clause B: first read is findByIdForUpdate (pessimistic lock)
        Tournament draftTournament =
                new Tournament(tournamentId, "T", "best-of-1", "r1", "v1", "g1", "DRAFT", null);
        when(tournamentRepository.findByIdForUpdate(tournamentId)).thenReturn(draftTournament);
        when(tournamentRepository.save(any(Tournament.class))).thenReturn(draftTournament);
        when(lifecycleService.markPlanned(tournamentId)).thenReturn(draftTournament);

        Phase savedPhase =
                new Phase(
                        UUID.randomUUID(),
                        tournamentId,
                        1,
                        "Phase 1",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.save(any(Phase.class))).thenReturn(savedPhase);

        // E51S18 DEC-59 Clause A: siegerehrung now also requires participating teams (N avatars
        // per phase uniformly). Stub one participating team so loadParticipatingTeams() succeeds.
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findByTournamentId(tournamentId))
                .thenReturn(
                        List.of(
                                new Team(
                                        teamId,
                                        tournamentId,
                                        1,
                                        "Team 1",
                                        true,
                                        false,
                                        false,
                                        null)));

        List<UUID> result = draftService.apply(tournamentId, config);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(savedPhase.getId());
        verify(phaseRepository, times(1)).save(any(Phase.class));
    }

    /**
     * AC-TDD-DraftService: apply with two sections creates two Phases.
     *
     * <p>E51S02: Section 1 is roundRobin — triggers avatar persistence. teamRepository must return
     * at least one participating team (empty → IllegalArgumentException per
     * AC-ERROR-HANDLING-EMPTY-PARTICIPATING-TEAMS). The test stubs a single participating team so
     * that the avatar-persistence path completes without error.
     */
    @Test
    void apply_withTwoSections_createsTwoPhases() {
        UUID tournamentId = UUID.randomUUID();
        // Section 1: roundRobin (non-siegerehrung → triggers avatar persistence)
        // Section 2: last — must be siegerehrung per D-10 invariant (E48S01)
        DraftConfig config = new DraftConfig(List.of(simpleSection(1), lastSection(2)));

        Tournament draftTournament =
                new Tournament(tournamentId, "T", "best-of-1", "r1", "v1", "g1", "DRAFT", null);
        when(tournamentRepository.findByIdForUpdate(tournamentId)).thenReturn(draftTournament);
        when(tournamentRepository.save(any(Tournament.class))).thenReturn(draftTournament);
        when(lifecycleService.markPlanned(tournamentId)).thenReturn(draftTournament);

        Phase phase1 =
                new Phase(
                        UUID.randomUUID(),
                        tournamentId,
                        1,
                        "Phase 1",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        Phase phase2 =
                new Phase(
                        UUID.randomUUID(),
                        tournamentId,
                        2,
                        "Phase 2",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.save(any(Phase.class))).thenReturn(phase1, phase2);

        // E51S02: stub one participating team to satisfy loadParticipatingTeams()
        // (query collaborator — no verify() per feedback_testing_verify_scope.md)
        UUID teamId = UUID.randomUUID();
        Team participatingTeam =
                new Team(teamId, tournamentId, 1, "Team 1", true, false, false, null);
        when(teamRepository.findByTournamentId(tournamentId))
                .thenReturn(List.of(participatingTeam));
        // teamAvatarRepository.save() is lenient (Mockito lenient by default for return value)

        List<UUID> result = draftService.apply(tournamentId, config);

        assertThat(result).hasSize(2);
        verify(phaseRepository, times(2)).save(any(Phase.class));
    }

    /** AC-TDD-DraftService: apply with a section containing a break persists PhaseBreak. */
    @Test
    void apply_withSectionContainingBreak_persistsPhaseBreak() {
        UUID tournamentId = UUID.randomUUID();
        DraftConfig config = new DraftConfig(List.of(sectionWithBreak(1)));

        Tournament draftTournament =
                new Tournament(tournamentId, "T", "best-of-1", "r1", "v1", "g1", "DRAFT", null);
        when(tournamentRepository.findByIdForUpdate(tournamentId)).thenReturn(draftTournament);
        when(tournamentRepository.save(any(Tournament.class))).thenReturn(draftTournament);
        when(lifecycleService.markPlanned(tournamentId)).thenReturn(draftTournament);

        Phase savedPhase =
                new Phase(
                        UUID.randomUUID(),
                        tournamentId,
                        1,
                        "Phase 1",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.save(any(Phase.class))).thenReturn(savedPhase);

        // E51S18 DEC-59 Clause A: siegerehrung now also requires participating teams (N avatars
        // per phase uniformly). Stub one participating team so loadParticipatingTeams() succeeds.
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findByTournamentId(tournamentId))
                .thenReturn(
                        List.of(
                                new Team(
                                        teamId,
                                        tournamentId,
                                        1,
                                        "Team 1",
                                        true,
                                        false,
                                        false,
                                        null)));

        draftService.apply(tournamentId, config);

        verify(phaseBreakRepository, times(1)).save(any(PhaseBreak.class));
    }

    /**
     * AC-ERROR-HANDLING-DRAFT-ALREADY-APPLIED-COLLAPSED (E48S22): re-apply on a non-DRAFT
     * tournament (e.g. PLANNED) throws TournamentNotInDraftException (fails-fast at precondition).
     *
     * <p>DraftAlreadyAppliedException is collapsed per E48S22 — the DRAFT-precondition check (step
     * b) fires before any phase creation, so a re-apply attempt on a PLANNED tournament is caught
     * at the same 409-path.
     *
     * <p>Migrated from {@code apply_whenPhasesAlreadyExist_throwsDraftAlreadyAppliedException} per
     * AC-ERROR-HANDLING-DRAFT-ALREADY-APPLIED-COLLAPSED.
     */
    @Test
    void apply_whenTournamentNotDraft_throwsTournamentNotInDraftException() {
        UUID tournamentId = UUID.randomUUID();
        DraftConfig config = new DraftConfig(List.of(simpleSection(1)));

        // Tournament is already PLANNED (post first apply) — DRAFT precondition fails
        Tournament plannedTournament =
                new Tournament(tournamentId, "T", "best-of-1", "r1", "v1", "g1", "PLANNED", null);
        when(tournamentRepository.findByIdForUpdate(tournamentId)).thenReturn(plannedTournament);

        assertThatThrownBy(() -> draftService.apply(tournamentId, config))
                .isInstanceOf(TournamentNotInDraftException.class);

        verify(phaseRepository, never()).save(any(Phase.class));
    }

    // =========================================================================
    // preview() — siegerehrung branch (AC-TEST-COMPUTE-PREVIEW-SIEGEREHRUNG-ZERO-RED, E48S09)
    // =========================================================================

    /**
     * AC-TEST-COMPUTE-PREVIEW-SIEGEREHRUNG-ZERO-RED: siegerehrung gameMode → 0 matches, 0 laps, 0
     * totalMatches; estimatedTimeMinutes = intra-phase breaks + sectionBreakTimeMinutes only.
     *
     * <p>Fixture: gameMode="awardCeremony", groupCount=1, lapTimeMinutes=15,
     * sectionBreakTimeMinutes=30, lapBreakTimeMinutes=2, breaks=[10min, 5min].
     *
     * <p>Expected estimatedTimeMinutes = 10 + 5 + 30 = 45 (lapTime=0, interLapBreaks=0).
     *
     * <p>RED-first per DEC-22 Iron Law (E48S09, Q-1a): written before the siegerehrung branch is
     * added to computePreview.
     */
    @Test
    void preview_awardCeremony_section_returnsZeroMatchesAndPreservesBreakTime() {
        DraftBreak break1 = new DraftBreak(1, 10, "Pause 1");
        DraftBreak break2 = new DraftBreak(2, 5, "Pause 2");
        DraftSection section =
                new DraftSection(
                        1,
                        "team_number",
                        1,
                        "awardCeremony",
                        2, // lapBreakTimeMinutes
                        30, // sectionBreakTimeMinutes
                        15, // lapTimeMinutes
                        1,
                        List.of(break1, break2));
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 12, 3, null);

        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).getMatchesPerGroup())
                .as("siegerehrung → matchesPerGroup = 0")
                .isEqualTo(0);
        assertThat(result.sections().get(0).getTotalLaps())
                .as("siegerehrung → totalLaps = 0")
                .isEqualTo(0);
        assertThat(result.sections().get(0).getTotalMatches())
                .as("siegerehrung → totalMatches = 0")
                .isEqualTo(0);
        assertThat(result.sections().get(0).getEstimatedTimeMinutes())
                .as(
                        "siegerehrung → estimatedTimeMinutes = intra-breaks(15) + sectionBreak(30)"
                                + " = 45")
                .isEqualTo(45);
    }

    /**
     * AC-ERROR-HANDLING-NULL-OR-MISSING-GAMEMODE: null gameMode falls through to field-aware
     * round-robin formula — no NPE, no exception.
     *
     * <p>Fixture: gameMode=null, 4 teams, 1 group, fieldCount=10 → teamConflict=2, eff=2,
     * matches=6, laps=ceil(6/2)=3. estimatedTimeMinutes = 15*3 + 0 + 0 + 0 = 45.
     *
     * <p>RED-first per DEC-22 Iron Law (E48S09, Q-1a): null gameMode triggers NPE on {@code
     * "awardCeremony".equals(section.getGameMode())} ONLY if implemented as {@code
     * section.getGameMode().equals("awardCeremony")} — using the literal first makes the NPE
     * impossible. This test verifies the fall-through behavior for defensive completeness.
     */
    @Test
    void preview_nullGameMode_fallsThroughToRoundRobinFormula() {
        DraftSection section =
                new DraftSection(
                        1,
                        "team_number",
                        1,
                        null, // gameMode = null → falls through to field-aware formula
                        0,
                        0,
                        15, // lapTimeMinutes
                        1,
                        List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        // null gameMode → RR formula: 4 teams / 1 group, fieldCount=10
        // teamConflict=floor(4/2)*1=2, eff=min(2,10)=2, matches=6, laps=ceil(6/2)=3
        DraftPreviewResult result = draftService.preview(config, 4, 10, null);

        assertThat(result.sections().get(0).getTotalMatches())
                .as("null gameMode falls through to RR formula: 6 matches")
                .isEqualTo(6);
        assertThat(result.sections().get(0).getTotalLaps())
                .as("null gameMode falls through to RR formula: 3 laps")
                .isEqualTo(3);
    }

    // =========================================================================
    // preview() — timeline wiring (E48S12)
    // =========================================================================

    /**
     * AC-TEST-DRAFT-SERVICE-PREVIEW-TIMELINE-NULL-START-RED (E48S12): when {@code plannedStartTime
     * == null}, {@code DraftPreviewResult.timeline} is {@code List.of()} (current contract
     * preserved).
     *
     * <p>RED-first per DEC-22 Iron Law: written before the 4-arg {@code preview(DraftConfig, int,
     * int, LocalTime)} signature exists. This test demonstrates the empty-timeline invariant when
     * no start time is set.
     *
     * @see <a href="E48S12">E48S12 — Wire TimelineCalculationService into
     *     DraftService.preview()</a>
     * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
     */
    @Test
    void preview_withNullPlannedStartTime_returnsEmptyTimeline() {
        DraftSection section =
                new DraftSection(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 4, 3, null);

        assertThat(result.timeline())
                .as(
                        "plannedStartTime=null → timeline must be empty"
                                + " (AC-ERROR-HANDLING-NULL-SAFETY)")
                .isEmpty();
    }

    /**
     * AC-TEST-DRAFT-SERVICE-PREVIEW-TIMELINE-POPULATED-RED (E48S12): when {@code plannedStartTime
     * != null} and the config has 2 phases (RR + Siegerehrung), {@code DraftPreviewResult.timeline}
     * is non-empty and contains at least one {@code MATCH_ROUND} entry for the RR phase and a
     * zero-duration {@code MATCH_ROUND} marker for the Siegerehrung phase.
     *
     * <p>Fixture: 12 teams, 1 group (groupCount=1), 2 phases (roundRobin then siegerehrung). For
     * the RR phase: totalLaps = 15 laps (ceil(66/4) — see field formula). For Siegerehrung:
     * lapCount=0 → single zero-duration MATCH_ROUND marker.
     *
     * <p>The service is mocked for the timeline calculation; the test only checks that the service
     * is invoked and its result is reflected in {@code DraftPreviewResult.timeline}.
     *
     * <p>RED-first per DEC-22 Iron Law (E48S12, Q-1a): written before the timeline wiring lands.
     *
     * @see <a href="E48S12">E48S12 — AC-TEST-DRAFT-SERVICE-PREVIEW-TIMELINE-POPULATED-RED</a>
     * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
     */
    @Test
    void preview_withPlannedStartTime_returnsPopulatedTimeline() {
        LocalTime startTime = LocalTime.of(9, 0);
        DraftSection rrSection =
                new DraftSection(1, "team_number", 1, "roundRobin", 2, 10, 15, 1, List.of());
        DraftSection sieg =
                new DraftSection(2, "team_number", 1, "awardCeremony", 0, 0, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(rrSection, sieg));

        // Stub: when the service is called for any phase list at the given start time,
        // return a representative MATCH_ROUND entry. The implementation may call calculate()
        // per phase (strategy i) or for all phases at once (strategy ii).
        TimelineEntry entry1 =
                new TimelineEntry(
                        1,
                        1,
                        TimelineEntryType.MATCH_ROUND,
                        startTime,
                        startTime.plusMinutes(15),
                        null);
        when(timelineCalculationService.calculate(eq(startTime), any(), anyInt()))
                .thenReturn(List.of(entry1));

        DraftPreviewResult result = draftService.preview(config, 12, 3, startTime);

        assertThat(result.timeline())
                .as("plannedStartTime non-null → timeline must be populated (E48S12)")
                .isNotEmpty();
        assertThat(result.timeline().get(0).startTime())
                .as("first timeline entry startTime must match plannedStartTime")
                .isEqualTo(startTime);
        assertThat(result.timeline().stream().anyMatch(e -> "MATCH_ROUND".equals(e.type())))
                .as("timeline must contain at least one MATCH_ROUND entry")
                .isTrue();
    }
}
