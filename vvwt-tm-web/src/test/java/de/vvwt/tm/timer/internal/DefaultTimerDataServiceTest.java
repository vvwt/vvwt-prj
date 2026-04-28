package de.vvwt.tm.timer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.vvwt.tm.timer.InvalidTimerUrlException;
import de.vvwt.tm.timer.NoActiveTournamentException;
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
 * <p>All 8 service-method-coverage ACs are covered: success-path, empty-schedule, invalid-url,
 * draft-status, cancelled-status, accessible-statuses-invariant, break-type-mapping,
 * audio-URL-Wave2.
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

    @InjectMocks private DefaultTimerDataService service;

    private UUID tournamentId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tournamentId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    // ── Helper factories ──────────────────────────────────────────────────────

    private Tournament activeTournament() {
        Tournament t = new Tournament();
        t.setId(tournamentId);
        t.setTenantId(tenantId);
        t.setDescription("Test Tournament");
        t.setStatus("ACTIVE");
        return t;
    }

    private Tournament tournamentWithStatus(String status) {
        Tournament t = new Tournament();
        t.setId(tournamentId);
        t.setTenantId(tenantId);
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

    // ── AC-BUILD-TIMER-DATA-SUCCESS-PATH ──────────────────────────────────────

    /**
     * AC-BUILD-TIMER-DATA-SUCCESS-PATH: tournament with phases + matches + audio → full response
     * with all 11 fields populated.
     */
    @Test
    void buildTimerData_returnsFullResponseForActiveTournament() {
        // Arrange
        Tournament tournament = activeTournament();
        tournament.setPlannedStartTime(LocalTime.of(9, 0));

        Phase p1 = phase(1, 2);
        p1.setStatus("ACTIVE");
        Match m1 = matchWithLap(p1.getId(), 1);
        Match m2 = matchWithLap(p1.getId(), 2);

        TimelineEntry roundEntry =
                new TimelineEntry(
                        1,
                        1,
                        TimelineEntryType.MATCH_ROUND,
                        LocalTime.of(9, 0),
                        LocalTime.of(9, 15),
                        null);

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1, m2));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());
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
            TimelineEntryType timelineType, String expectedBreakTypeName) {
        // Arrange: tournament with start time, one phase, one match, timeline returns break entry
        Tournament tournament = activeTournament();
        tournament.setPlannedStartTime(LocalTime.of(9, 0));

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

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p1));
        when(matchRepository.findByPhaseId(p1.getId())).thenReturn(List.of(m1));
        when(phaseBreakRepository.findByPhaseId(p1.getId())).thenReturn(Collections.emptyList());
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
}
