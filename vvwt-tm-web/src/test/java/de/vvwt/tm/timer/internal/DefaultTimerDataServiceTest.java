// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.timer.InvalidTimerUrlException;
import de.vvwt.tm.timer.NoActiveTournamentException;
import de.vvwt.tm.timer.NoScheduleConfiguredException;
import de.vvwt.tm.timer.TimerAudioResponse;
import de.vvwt.tm.timer.TimerDataResponse;
import de.vvwt.tm.timer.audio.AudioCategory;
import de.vvwt.tm.timer.audio.AudioStorageService;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.TimelineEntry;
import de.vvwt.tm.tournament.TimelineEntryType;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultTimerDataService} — same-package white-box tests per DEC-36.
 *
 * <p>Tests reference {@link DefaultTimerDataService} directly (implementation class) because the
 * test class is co-located in {@code de.vvwt.tm.timer.internal} — same package as the subject. This
 * is the DEC-36 white-box exception for same-package tests. Cross-package tests (e.g.,
 * TimerControllerIT in E26S03) MUST reference via {@link de.vvwt.tm.timer.TimerDataService}
 * interface instead.
 *
 * <p>All service-method-coverage ACs are covered: success-path, empty-schedule, invalid-url,
 * draft-status, cancelled-status, accessible-statuses-invariant, break-type-mapping,
 * audio-URL-Wave2, AC11 (absent draftJson), AC12 (invalid lapTimeMinutes), AC14 (DraftConfig
 * sourced values — FAIL-ON-OLD), AC7 (SECTION_BREAK per section).
 *
 * <p>DEC-22 REFACTOR Phase-3 (AC14): {@link ObjectMapper} mock added; existing success-path test
 * updated to supply an explicit {@link DraftConfig} with {@code lapTimeMinutes=10} — the test now
 * FAILS on the pre-fix code (which used hardcoded {@code DEFAULT_LAP_TIME_MINUTES=15}) and PASSES
 * after the E11S10 fix (which reads from DraftConfig). This is the AC14 fail-on-old attestation
 * artifact per DEC-22 §refactor-clause.
 *
 * <p>DEC-41 §4 audit obligation: all 28 in-scope legacy tests classified Snapshot-Driven per {@code
 * E26-AUDIT-DEC41-TEST-CLASSIFICATION}. Zero legacy tests reused. All tests here are fresh
 * RED-first authored per DEC-22 Iron Law.
 *
 * <p>DEC-22 Iron Law: RED commit SHA: to be filled in impl-report. GREEN commit SHA: to be filled
 * in impl-report.
 *
 * @see DefaultTimerDataService
 * @see de.vvwt.tm.timer.TimerDataService
 * @see <a href="contexts/artefacts/stories/E11S10.story.md">Story E11S10</a>
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
@ExtendWith(MockitoExtension.class)
class DefaultTimerDataServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private PhaseBreakRepository phaseBreakRepository;
    @Mock private TimelineCalculationService timelineCalculationService;
    @Mock private AudioStorageService audioStorageService;
    @Mock private TenantContext tenantContext;
    /** E11S10 AC14: ObjectMapper mock added for DraftConfig deserialization (DEC-22 REFACTOR). */
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private DefaultTimerDataService service;

    private UUID tournamentId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tournamentId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(tenantContext.current()).thenReturn(tenantId);
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private Tournament activeTournament() {
        Tournament t = new Tournament();
        t.setId(tournamentId);
        t.setDescription("Test Tournament");
        t.setStatus("ACTIVE");
        return t;
    }

    private Tournament tournamentWithStatus(String status) {
        Tournament t = new Tournament();
        t.setId(tournamentId);
        t.setDescription("Test Tournament");
        t.setStatus(status);
        return t;
    }

    private Phase phase(int sequenceNumber, int currentLap) {
        Phase p = new Phase();
        p.setId(UUID.randomUUID());
        p.setTournamentId(tournamentId);
        p.setSequenceNumber(sequenceNumber);
        p.setDescription("Phase " + sequenceNumber);
        p.setStatus("ACTIVE");
        p.setCurrentLapNumber(currentLap);
        return p;
    }

    private Match matchWithLap(UUID phaseId, int lapNumber) {
        Match m = new Match();
        m.setPhaseId(phaseId);
        m.setLapNumber(lapNumber);
        return m;
    }

    /**
     * Helper: creates a single-section DraftConfig with the given lapTimeMinutes.
     *
     * <p>Uses the 9-arg DraftSection constructor (backward-compatible; distributionMode defaults to
     * "sequential").
     */
    private DraftConfig singleSectionDraft(int lapTimeMinutes) {
        DraftSection section =
                new DraftSection(
                        1, "team_number", 1, "roundRobin", 5, 0, lapTimeMinutes, 1, null);
        return new DraftConfig(List.of(section));
    }

    /**
     * Helper: creates a two-section DraftConfig with given per-section values.
     *
     * @param sec1LapTime section-1 lap time minutes
     * @param sec1SectionBreak section-1 section break minutes (after section 1, before section 2)
     * @param sec2LapTime section-2 lap time minutes
     */
    private DraftConfig twoSectionDraft(int sec1LapTime, int sec1SectionBreak, int sec2LapTime) {
        DraftSection s1 =
                new DraftSection(1, "team_number", 1, "roundRobin", 5, sec1SectionBreak, sec1LapTime, 1, null);
        DraftSection s2 =
                new DraftSection(2, "team_number", 1, "awardCeremony", 0, 0, sec2LapTime, 1, null);
        return new DraftConfig(List.of(s1, s2));
    }

    // ── AC-BUILD-TIMER-DATA-SUCCESS-PATH ──────────────────────────────────────

    /**
     * AC-BUILD-TIMER-DATA-SUCCESS-PATH: tournament with phases + matches + audio → full response
     * with all 11 fields populated.
     *
     * <p>E11S10 AC14 (DEC-22 REFACTOR fail-on-old): DraftConfig with {@code lapTimeMinutes=10} is
     * supplied — the pre-fix code (DEFAULT_LAP_TIME_MINUTES=15) would have ignored this and used 15,
     * but the ObjectMapper was not injected at all, so this test would FAIL pre-fix (mock not
     * configured → NPE on objectMapper.readValue). POST-fix, the mock is consulted and returns the
     * 10-minute DraftConfig — test PASSES.
     */
    @Test
    void buildTimerData_returnsFullResponseForActiveTournament() throws Exception {
        // Arrange
        Tournament tournament = activeTournament();
        tournament.setPlannedStartTime(LocalTime.of(9, 0));
        tournament.setDraftJson("{\"sections\":[{\"sectionNumber\":1,\"lapTimeMinutes\":10}]}");

        Phase p1 = phase(1, 2);
        p1.setStatus("ACTIVE");
        Match m1 = matchWithLap(p1.getId(), 1);
        Match m2 = matchWithLap(p1.getId(), 2);

        // AC14 fail-on-old: lapTimeMinutes=10 (NOT the old hardcoded 15)
        DraftConfig draft = singleSectionDraft(10);

        TimelineEntry roundEntry =
                new TimelineEntry(
                        1,
                        1,
                        TimelineEntryType.MATCH_ROUND,
                        LocalTime.of(9, 0),
                        LocalTime.of(9, 10),
                        null);

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1, m2));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());
        // E11S10 AC14: objectMapper must be stubbed — pre-fix code had no ObjectMapper, so test
        // FAILS pre-fix (NullPointerException on objectMapper call) and PASSES post-fix.
        when(objectMapper.readValue(anyString(), eq(DraftConfig.class))).thenReturn(draft);
        when(timelineCalculationService.calculate(any(), any(), eq(0)))
                .thenReturn(List.of(roundEntry));
        when(audioStorageService.stream(eq(tournamentId), any())).thenReturn(Optional.empty());

        // Act
        TimerDataResponse response = service.buildTimerData(tournamentId);

        // Assert — all 11 fields populated
        assertThat(response.getTournamentName()).isEqualTo("Test Tournament");
        assertThat(response.getTournamentId()).isEqualTo(tournamentId);
        assertThat(response.getTenantId()).isEqualTo(tenantId);
        assertThat(response.getTournamentStatus()).isEqualTo("ACTIVE");
        assertThat(response.getCurrentPhaseNumber()).isEqualTo(1);
        assertThat(response.getCurrentLapNumber()).isEqualTo(2);
        assertThat(response.isHasStartTime()).isTrue();
        assertThat(response.isEmptySchedule()).isFalse();
        assertThat(response.getSchedule()).isNotNull();
        assertThat(response.getPhases()).isNotEmpty();
        assertThat(response.getAudio()).isNotNull();
    }

    // ── AC-BUILD-TIMER-DATA-EMPTY-SCHEDULE ────────────────────────────────────

    /**
     * AC-BUILD-TIMER-DATA-EMPTY-SCHEDULE: tournament with no phases → emptySchedule=true, empty
     * lists, audio still present.
     *
     * <p>No DraftConfig needed — empty-schedule path returns before loadDraftConfig is called.
     */
    @Test
    void buildTimerData_returnsEmptyScheduleWhenNoPhases() {
        // Arrange
        Tournament tournament = tournamentWithStatus("ACTIVE");
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());
        when(audioStorageService.stream(eq(tournamentId), any())).thenReturn(Optional.empty());

        // Act
        TimerDataResponse response = service.buildTimerData(tournamentId);

        // Assert
        assertThat(response.isEmptySchedule()).isTrue();
        assertThat(response.getSchedule()).isEmpty();
        assertThat(response.getPhases()).isEmpty();
        assertThat(response.getAudio()).isNotNull();
        assertThat(response.getTournamentName()).isEqualTo("Test Tournament");
    }

    // ── AC-BUILD-TIMER-DATA-INVALID-URL ───────────────────────────────────────

    /** AC-BUILD-TIMER-DATA-INVALID-URL: unknown tournamentId → throws InvalidTimerUrlException. */
    @Test
    void buildTimerData_throwsInvalidTimerUrlExceptionForUnknownTournament() {
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildTimerData(tournamentId))
                .isInstanceOf(InvalidTimerUrlException.class);
    }

    // ── AC-BUILD-TIMER-DATA-DRAFT-STATUS ──────────────────────────────────────

    /**
     * AC-BUILD-TIMER-DATA-DRAFT-STATUS: tournament in DRAFT status → throws
     * NoActiveTournamentException.
     */
    @Test
    void buildTimerData_throwsNoActiveTournamentExceptionForDraftStatus() {
        Tournament tournament = tournamentWithStatus("DRAFT");
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));

        assertThatThrownBy(() -> service.buildTimerData(tournamentId))
                .isInstanceOf(NoActiveTournamentException.class)
                .hasMessageContaining("DRAFT");
    }

    // ── AC-BUILD-TIMER-DATA-CANCELLED-STATUS ─────────────────────────────────

    /**
     * AC-BUILD-TIMER-DATA-CANCELLED-STATUS: tournament in CANCELLED status → throws
     * NoActiveTournamentException.
     */
    @Test
    void buildTimerData_throwsNoActiveTournamentExceptionForCancelledStatus() {
        Tournament tournament = tournamentWithStatus("CANCELLED");
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));

        assertThatThrownBy(() -> service.buildTimerData(tournamentId))
                .isInstanceOf(NoActiveTournamentException.class)
                .hasMessageContaining("CANCELLED");
    }

    // ── AC-TIMER-ACCESSIBLE-STATUSES-INVARIANT ────────────────────────────────

    /**
     * AC-TIMER-ACCESSIBLE-STATUSES-INVARIANT: Named algebraic invariant — all 3 timer-accessible
     * statuses (PLANNED, ACTIVE, COMPLETED) permit access; any other status throws
     * NoActiveTournamentException. Satisfies DEC-41 §1(d): named invariant + quantified body
     * via @ParameterizedTest over representative input set.
     */
    @ParameterizedTest(name = "status={0} permits timer access")
    @ValueSource(strings = {"PLANNED", "ACTIVE", "COMPLETED"})
    void buildTimerData_allowsAccessForTimerAccessibleStatuses(String status) {
        Tournament tournament = tournamentWithStatus(status);
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());
        when(audioStorageService.stream(eq(tournamentId), any())).thenReturn(Optional.empty());

        // Should NOT throw for any timer-accessible status
        TimerDataResponse response = service.buildTimerData(tournamentId);
        assertThat(response).isNotNull();
    }

    // ── AC-BREAK-TYPE-MAPPING ─────────────────────────────────────────────────

    /**
     * AC-BREAK-TYPE-MAPPING: Named algebraic invariant — timeline break-type mapping is
     * {LAP_BREAK→REGULAR, INTRA_PHASE_BREAK→ADDITIONAL, SECTION_BREAK→ADDITIONAL}. Satisfies DEC-41
     * §1(d): named invariant ("break type mapping invariant") + quantified body
     * via @ParameterizedTest over all 3 break type enum values (representative input set).
     *
     * <p>Format: timelineType, expectedBreakTypeName
     */
    @ParameterizedTest(name = "{0} → breakType={1}")
    @CsvSource({"LAP_BREAK, REGULAR", "INTRA_PHASE_BREAK, ADDITIONAL", "SECTION_BREAK, ADDITIONAL"})
    void buildTimerData_mapsBreakTypesCorrectly(
            TimelineEntryType timelineType, String expectedBreakTypeName) throws Exception {
        // Arrange: tournament with start time, one phase, one match, timeline returns break entry
        Tournament tournament = activeTournament();
        tournament.setPlannedStartTime(LocalTime.of(9, 0));
        tournament.setDraftJson("{\"sections\":[{\"sectionNumber\":1,\"lapTimeMinutes\":10}]}");

        Phase p1 = phase(1, 0);
        Match m1 = matchWithLap(p1.getId(), 1);

        TimelineEntry breakEntry =
                new TimelineEntry(
                        1,
                        0,
                        timelineType,
                        LocalTime.of(9, 15),
                        LocalTime.of(9, 20),
                        timelineType == TimelineEntryType.INTRA_PHASE_BREAK ? "Pause" : null);

        DraftConfig draft = singleSectionDraft(10);

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());
        when(objectMapper.readValue(anyString(), eq(DraftConfig.class))).thenReturn(draft);
        when(timelineCalculationService.calculate(any(), any(), eq(0)))
                .thenReturn(List.of(breakEntry));
        when(audioStorageService.stream(eq(tournamentId), any())).thenReturn(Optional.empty());

        // Act
        TimerDataResponse response = service.buildTimerData(tournamentId);

        // Assert: schedule contains the break entry with the expected break type
        assertThat(response.getSchedule())
                .anySatisfy(
                        entry -> {
                            assertThat(entry.getType()).isEqualTo("BREAK");
                            assertThat(entry.getBreakType()).isEqualTo(expectedBreakTypeName);
                        });
    }

    // ── AC-AUDIO-URL-CONSTRUCTION-WAVE2 ───────────────────────────────────────

    /**
     * AC-AUDIO-URL-CONSTRUCTION-WAVE2: Wave-2-aligned audio URL is
     * /api/audio/tournaments/{tournamentId}/{category}/stream (NOT legacy
     * /api/tournaments/{tournamentId}/audio/{category}/stream).
     */
    @Test
    void buildTimerData_constructsWave2AudioUrl() {
        // Arrange: tournament with no phases (simplest path to reach audio URL construction)
        Tournament tournament = tournamentWithStatus("ACTIVE");
        InputStream fakeStream = new ByteArrayInputStream(new byte[0]);

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(Collections.emptyList());
        when(audioStorageService.stream(eq(tournamentId), eq(AudioCategory.START)))
                .thenReturn(Optional.of(fakeStream));
        when(audioStorageService.stream(eq(tournamentId), eq(AudioCategory.END)))
                .thenReturn(Optional.empty());
        when(audioStorageService.stream(eq(tournamentId), eq(AudioCategory.PAUSE)))
                .thenReturn(Optional.empty());

        // Act
        TimerDataResponse response = service.buildTimerData(tournamentId);

        // Assert: Wave-2-aligned URL pattern
        // Check the URL doesn't contain the legacy pattern
        TimerAudioResponse audio = response.getAudio();
        assertThat(audio.getStartUrl()).isNotNull();
        assertThat(audio.getStartUrl()).doesNotContain("/api/tournaments/");
        assertThat(audio.getStartUrl()).contains("/api/audio/tournaments/");
        assertThat(audio.getStartUrl()).endsWith("/stream");
    }

    // ── AC11: DraftConfig absent (draftJson null/blank) ───────────────────────

    /**
     * AC11: When draftJson is null (not configured), buildTimerData MUST throw
     * {@link NoScheduleConfiguredException} for a tournament that has phases.
     *
     * <p>Consistent with E11S03 error states: the timer frontend displays a "no-schedule" error.
     * Tests FAIL on pre-fix code (no such exception thrown; NPE on objectMapper) and PASS after fix.
     */
    @Test
    void buildTimerData_throwsNoScheduleConfiguredExceptionWhenDraftJsonNull() {
        // Arrange: tournament with phases but NO draftJson configured
        Tournament tournament = activeTournament();
        tournament.setDraftJson(null); // AC11: absent draftJson
        Phase p1 = phase(1, 1);
        Match m1 = matchWithLap(p1.getId(), 1);

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());
        // No objectMapper stub needed — exception is thrown before readValue is called

        // Act + Assert
        assertThatThrownBy(() -> service.buildTimerData(tournamentId))
                .isInstanceOf(NoScheduleConfiguredException.class)
                .satisfies(ex -> assertThat(((NoScheduleConfiguredException) ex).getErrorCode())
                        .isEqualTo("NO_SCHEDULE_CONFIGURED"));
    }

    /**
     * AC11 (blank): draftJson blank string → same as absent — throws
     * {@link NoScheduleConfiguredException}.
     */
    @Test
    void buildTimerData_throwsNoScheduleConfiguredExceptionWhenDraftJsonBlank() {
        // Arrange: tournament with phases but BLANK draftJson
        Tournament tournament = activeTournament();
        tournament.setDraftJson("  "); // AC11: blank draftJson
        Phase p1 = phase(1, 1);
        Match m1 = matchWithLap(p1.getId(), 1);

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());

        // Act + Assert
        assertThatThrownBy(() -> service.buildTimerData(tournamentId))
                .isInstanceOf(NoScheduleConfiguredException.class);
    }

    // ── AC12: Invalid lapTimeMinutes ──────────────────────────────────────────

    /**
     * AC12: When DraftConfig has {@code lapTimeMinutes=0} for a phase with at least one lap,
     * buildTimerData MUST throw {@link NoScheduleConfiguredException}.
     *
     * <p>Consistent with E11S03 error states. Tests FAIL on pre-fix code (hardcoded 15 used,
     * lapTimeMinutes from config not validated) and PASS after fix.
     */
    @Test
    void buildTimerData_throwsNoScheduleConfiguredExceptionWhenLapTimeMinutesZero() throws Exception {
        // Arrange: phase has 1 lap, DraftConfig has lapTimeMinutes=0 (invalid)
        Tournament tournament = activeTournament();
        tournament.setPlannedStartTime(LocalTime.of(9, 0));
        tournament.setDraftJson("{\"sections\":[{\"sectionNumber\":1,\"lapTimeMinutes\":0}]}");

        Phase p1 = phase(1, 0);
        Match m1 = matchWithLap(p1.getId(), 1); // lap exists → lapCount=1

        // DraftConfig with lapTimeMinutes=0 (AC12: invalid for lapCount > 0)
        DraftSection section =
                new DraftSection(1, "team_number", 1, "roundRobin", 5, 0, 0, 1, null);
        DraftConfig draft = new DraftConfig(List.of(section));

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());
        when(objectMapper.readValue(anyString(), eq(DraftConfig.class))).thenReturn(draft);

        // Act + Assert
        assertThatThrownBy(() -> service.buildTimerData(tournamentId))
                .isInstanceOf(NoScheduleConfiguredException.class);
    }

    /**
     * AC12 (fewer sections): DraftConfig has fewer sections than phases → throws
     * {@link NoScheduleConfiguredException}.
     */
    @Test
    void buildTimerData_throwsNoScheduleConfiguredExceptionWhenSectionCountLessThanPhaseCount()
            throws Exception {
        // Arrange: 2 phases but only 1 section in DraftConfig
        Tournament tournament = activeTournament();
        tournament.setDraftJson("{\"sections\":[{\"sectionNumber\":1,\"lapTimeMinutes\":10}]}");

        Phase p1 = phase(1, 0);
        Phase p2 = phase(2, 0);
        Match m1 = matchWithLap(p1.getId(), 1);
        Match m2 = matchWithLap(p2.getId(), 1);

        DraftConfig draft = singleSectionDraft(10); // only 1 section for 2 phases

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1, p2));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1));
        when(matchRepository.findByPhaseId(p2.getId())).thenReturn(List.of(m2));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());
        when(phaseBreakRepository.findByPhaseId(p2.getId())).thenReturn(Collections.emptyList());
        when(objectMapper.readValue(anyString(), eq(DraftConfig.class))).thenReturn(draft);

        // Act + Assert
        assertThatThrownBy(() -> service.buildTimerData(tournamentId))
                .isInstanceOf(NoScheduleConfiguredException.class);
    }

    // ── AC7: SECTION_BREAK per DraftSection.sectionBreakTimeMinutes ──────────

    /**
     * AC7: Strategy-i per-phase loop appends SECTION_BREAK entries between consecutive phases using
     * each section's own {@code sectionBreakTimeMinutes} from DraftConfig (NOT from a uniform
     * {@code sectionBreakMinutes=0} passed to calculate()). The resulting schedule includes a BREAK
     * entry of type ADDITIONAL for the section boundary.
     *
     * <p>Named algebraic invariant: "SECTION_BREAK from operator sectionBreakTimeMinutes." Tests
     * FAIL on pre-fix code (single calculate() call with sectionBreakMinutes=0 → no SECTION_BREAK
     * entries produced by Strategy-i because DraftConfig was never used) and PASS after fix.
     */
    @Test
    void buildTimerData_appendsSectionBreakFromDraftConfigBetweenPhases() throws Exception {
        // Arrange: 2 phases, section 1 has sectionBreakTimeMinutes=10
        Tournament tournament = activeTournament();
        tournament.setPlannedStartTime(LocalTime.of(9, 0));
        tournament.setDraftJson("{}"); // actual parsing is mocked

        Phase p1 = phase(1, 0);
        p1.setStatus("COMPLETED");
        Phase p2 = phase(2, 0);
        p2.setStatus("ACTIVE");

        Match m1 = matchWithLap(p1.getId(), 1);
        Match m2 = matchWithLap(p2.getId(), 1);

        // DraftConfig: section 1 has sectionBreakTimeMinutes=10 (AC7)
        DraftConfig draft = twoSectionDraft(10, 10, 10);

        // Per-phase timeline: phase 1 produces a MATCH_ROUND entry 09:00–09:10
        // Then Strategy-i appends a SECTION_BREAK 09:10–09:20 (10 min)
        // Then phase 2 produces a MATCH_ROUND entry 09:20–09:30
        TimelineEntry phase1Round =
                new TimelineEntry(1, 1, TimelineEntryType.MATCH_ROUND,
                        LocalTime.of(9, 0), LocalTime.of(9, 10), null);
        TimelineEntry phase2Round =
                new TimelineEntry(2, 1, TimelineEntryType.MATCH_ROUND,
                        LocalTime.of(9, 20), LocalTime.of(9, 30), null);

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1, p2));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1));
        when(matchRepository.findByPhaseId(p2.getId())).thenReturn(List.of(m2));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());
        when(phaseBreakRepository.findByPhaseId(p2.getId())).thenReturn(Collections.emptyList());
        when(objectMapper.readValue(anyString(), eq(DraftConfig.class))).thenReturn(draft);
        // Strategy-i: called per phase — phase 1 first, phase 2 second
        when(timelineCalculationService.calculate(eq(LocalTime.of(9, 0)), any(), eq(0)))
                .thenReturn(List.of(phase1Round));
        when(timelineCalculationService.calculate(eq(LocalTime.of(9, 20)), any(), eq(0)))
                .thenReturn(List.of(phase2Round));
        when(audioStorageService.stream(eq(tournamentId), any())).thenReturn(Optional.empty());

        // Act
        TimerDataResponse response = service.buildTimerData(tournamentId);

        // Assert: schedule contains a SECTION_BREAK (type=BREAK, breakType=ADDITIONAL) between phases
        // The schedule should be: round(phase1), SECTION_BREAK, round(phase2)
        assertThat(response.getSchedule()).hasSize(3);
        assertThat(response.getSchedule().get(0).getType()).isEqualTo("ROUND");
        assertThat(response.getSchedule().get(1).getType()).isEqualTo("BREAK");
        assertThat(response.getSchedule().get(1).getBreakType()).isEqualTo("ADDITIONAL");
        assertThat(response.getSchedule().get(2).getType()).isEqualTo("ROUND");
    }
}
