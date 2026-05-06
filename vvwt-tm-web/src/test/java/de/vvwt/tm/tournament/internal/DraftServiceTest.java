package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.draft.DraftBreak;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.exceptions.DraftAlreadyAppliedException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RED — DraftService unit test (AC-TDD-DraftService).
 *
 * <p>Tests {@code preview(DraftConfig, int)} pure computation and {@code apply(UUID, DraftConfig)}
 * phase-creation delegation. Mocks Phase-aggregate collaborators from E21S03 — the collaborator's
 * persistence is already tested in E21S03 own DAO tests (E15S03 precedent).
 *
 * <p>Inventory: E21S01 line 171.
 *
 * @see DefaultDraftService
 * @see PhaseRepository
 * @see PhaseBreakRepository
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E21S03">E21S03 — Phase aggregate (mocked collaborator)</a>
 */
@ExtendWith(MockitoExtension.class)
class DraftServiceTest {

    @Mock private PhaseRepository phaseRepository;
    @Mock private PhaseBreakRepository phaseBreakRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private PhasePreparationService phasePreparationService;

    @InjectMocks private DefaultDraftService draftService;

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
                sectionNumber, "team_number", 1, "siegerehrung", 0, 0, 15, 1, List.of());
    }

    private static DraftSection sectionWithBreak(int sectionNumber) {
        DraftBreak breakItem = new DraftBreak(1, 10, "Pause");
        return new DraftSection(
                sectionNumber, "team_number", 1, "siegerehrung", 0, 0, 15, 1, List.of(breakItem));
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

        DraftPreviewResult result = draftService.preview(config, 6, 3);

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

        DraftPreviewResult result = draftService.preview(config, 12, 3);

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

        DraftPreviewResult result = draftService.preview(config, 12, 3);

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

        DraftPreviewResult result = draftService.preview(config, 12, 3);

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

        DraftPreviewResult result = draftService.preview(config, 12, 6);

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

        DraftPreviewResult result = draftService.preview(config, 6, 10);

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

        DraftPreviewResult result = draftService.preview(config, 8, 1);

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
        DraftPreviewResult result = draftService.preview(config, 1, 3);

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

        DraftPreviewResult result = draftService.preview(config, 4, 0);

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

        // 3-arg API (E48S10): fieldCount=10 (excess) → effective=2, laps=ceil(6/2)=3
        DraftPreviewResult result = draftService.preview(config, participatingTeamCount, 10);

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

        DraftPreviewResult result = draftService.preview(config, 4, 3);

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

        // PhaseRepository.findByTournamentId returns empty (no phases yet)
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of());
        // No participating teams — TeamAvatar distribution skipped
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());

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

        List<UUID> result = draftService.apply(tournamentId, config);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(savedPhase.getId());
        verify(phaseRepository, times(1)).save(any(Phase.class));
    }

    /** AC-TDD-DraftService: apply with two sections creates two Phases. */
    @Test
    void apply_withTwoSections_createsTwoPhases() {
        UUID tournamentId = UUID.randomUUID();
        // Section 2 is last — must be siegerehrung per D-10 invariant (E48S01)
        DraftConfig config = new DraftConfig(List.of(simpleSection(1), lastSection(2)));

        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());

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

        List<UUID> result = draftService.apply(tournamentId, config);

        assertThat(result).hasSize(2);
        verify(phaseRepository, times(2)).save(any(Phase.class));
    }

    /** AC-TDD-DraftService: apply with a section containing a break persists PhaseBreak. */
    @Test
    void apply_withSectionContainingBreak_persistsPhaseBreak() {
        UUID tournamentId = UUID.randomUUID();
        DraftConfig config = new DraftConfig(List.of(sectionWithBreak(1)));

        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());

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

        draftService.apply(tournamentId, config);

        verify(phaseBreakRepository, times(1)).save(any(PhaseBreak.class));
    }

    /**
     * AC-DRAFT-APPLY-IDEMPOTENCY: re-apply throws DraftAlreadyAppliedException (fails-fast). Legacy
     * behaviour confirmed from domain.DraftService.applyDraft() line 297–305.
     */
    @Test
    void apply_whenPhasesAlreadyExist_throwsDraftAlreadyAppliedException() {
        UUID tournamentId = UUID.randomUUID();
        DraftConfig config = new DraftConfig(List.of(simpleSection(1)));

        Phase existingPhase =
                new Phase(
                        UUID.randomUUID(),
                        tournamentId,
                        1,
                        "Phase 1",
                        "PENDING",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(existingPhase));

        assertThatThrownBy(() -> draftService.apply(tournamentId, config))
                .isInstanceOf(DraftAlreadyAppliedException.class);

        verify(phaseRepository, never()).save(any(Phase.class));
    }

    // =========================================================================
    // preview() — siegerehrung branch (AC-TEST-COMPUTE-PREVIEW-SIEGEREHRUNG-ZERO-RED, E48S09)
    // =========================================================================

    /**
     * AC-TEST-COMPUTE-PREVIEW-SIEGEREHRUNG-ZERO-RED: siegerehrung gameMode → 0 matches, 0 laps, 0
     * totalMatches; estimatedTimeMinutes = intra-phase breaks + sectionBreakTimeMinutes only.
     *
     * <p>Fixture: gameMode="siegerehrung", groupCount=1, lapTimeMinutes=15,
     * sectionBreakTimeMinutes=30, lapBreakTimeMinutes=2, breaks=[10min, 5min].
     *
     * <p>Expected estimatedTimeMinutes = 10 + 5 + 30 = 45 (lapTime=0, interLapBreaks=0).
     *
     * <p>RED-first per DEC-22 Iron Law (E48S09, Q-1a): written before the siegerehrung branch is
     * added to computePreview.
     */
    @Test
    void preview_siegerehrung_section_returnsZeroMatchesAndPreservesBreakTime() {
        DraftBreak break1 = new DraftBreak(1, 10, "Pause 1");
        DraftBreak break2 = new DraftBreak(2, 5, "Pause 2");
        DraftSection section =
                new DraftSection(
                        1,
                        "team_number",
                        1,
                        "siegerehrung",
                        2, // lapBreakTimeMinutes
                        30, // sectionBreakTimeMinutes
                        15, // lapTimeMinutes
                        1,
                        List.of(break1, break2));
        DraftConfig config = new DraftConfig(List.of(section));

        DraftPreviewResult result = draftService.preview(config, 12, 3);

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
     * "siegerehrung".equals(section.getGameMode())} ONLY if implemented as {@code
     * section.getGameMode().equals("siegerehrung")} — using the literal first makes the NPE
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
        DraftPreviewResult result = draftService.preview(config, 4, 10);

        assertThat(result.sections().get(0).getTotalMatches())
                .as("null gameMode falls through to RR formula: 6 matches")
                .isEqualTo(6);
        assertThat(result.sections().get(0).getTotalLaps())
                .as("null gameMode falls through to RR formula: 3 laps")
                .isEqualTo(3);
    }
}
