package de.vvwt.tm.domain.timer;

import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhaseBreak;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.audio.AudioCategory;
import de.vvwt.tm.domain.audio.AudioStorageService;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseBreakRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.domain.timeline.PhaseBreakConfig;
import de.vvwt.tm.domain.timeline.PhaseConfig;
import de.vvwt.tm.domain.timeline.TimelineCalculationService;
import de.vvwt.tm.domain.timeline.TimelineEntry;
import de.vvwt.tm.domain.timeline.TimelineEntryType;
import de.vvwt.tm.infrastructure.web.timer.dto.TimerAudioResponse;
import de.vvwt.tm.infrastructure.web.timer.dto.TimerDataResponse;
import de.vvwt.tm.infrastructure.web.timer.dto.TimerPhaseResponse;
import de.vvwt.tm.infrastructure.web.timer.dto.TimerScheduleEntryResponse;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service that assembles the timer data response for a tournament (E11S02).
 *
 * <p>Combines data from the tournament repository (metadata, status, start time),
 * phase and match repositories (schedule structure), phase break repository (breaks),
 * the timeline calculation service (wall-clock times), and the audio storage service
 * (audio file availability) into a single {@link TimerDataResponse}.
 *
 * <h2>Timeline calculation (AC2)</h2>
 * <p>Delegates to {@link TimelineCalculationService} — this service does NOT duplicate
 * timeline calculation logic. Lap counts are derived from match data (same approach as
 * {@link de.vvwt.tm.infrastructure.print.LaufzettelAssembler}). Lap time defaults of
 * 15 min/lap and 5 min/lap-break are used when no explicit configuration is stored.
 *
 * <h2>Break type mapping (AC3)</h2>
 * <ul>
 *   <li>{@link TimelineEntryType#LAP_BREAK} → {@link TimerBreakType#REGULAR}</li>
 *   <li>{@link TimelineEntryType#INTRA_PHASE_BREAK} → {@link TimerBreakType#ADDITIONAL}</li>
 *   <li>{@link TimelineEntryType#SECTION_BREAK} → {@link TimerBreakType#ADDITIONAL}</li>
 * </ul>
 *
 * <h2>Error conditions (AC7)</h2>
 * <ul>
 *   <li>Tournament not found → {@link InvalidTimerUrlException}</li>
 *   <li>Tournament in DRAFT or CANCELLED status → {@link NoActiveTournamentException}</li>
 *   <li>No phases configured → 200 with {@code emptySchedule=true}</li>
 * </ul>
 *
 * @see TimerDataResponse
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story E11S02</a>
 */
@Service
public class TimerDataService {

    /** Default lap time in minutes — mirrors LaufzettelAssembler.DEFAULT_LAP_TIME_MINUTES. */
    static final int DEFAULT_LAP_TIME_MINUTES = 15;

    /** Default lap break time in minutes — mirrors LaufzettelAssembler.DEFAULT_LAP_BREAK_MINUTES. */
    static final int DEFAULT_LAP_BREAK_MINUTES = 5;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Tournament statuses that are accessible via the timer (PLANNED, ACTIVE, COMPLETED). */
    private static final java.util.Set<String> TIMER_ACCESSIBLE_STATUSES =
            java.util.Set.of("PLANNED", "ACTIVE", "COMPLETED");

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final PhaseBreakRepository phaseBreakRepository;
    private final TimelineCalculationService timelineCalculationService;
    private final AudioStorageService audioStorageService;

    public TimerDataService(TournamentRepository tournamentRepository,
                             PhaseRepository phaseRepository,
                             MatchRepository matchRepository,
                             PhaseBreakRepository phaseBreakRepository,
                             TimelineCalculationService timelineCalculationService,
                             AudioStorageService audioStorageService) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.phaseBreakRepository = phaseBreakRepository;
        this.timelineCalculationService = timelineCalculationService;
        this.audioStorageService = audioStorageService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the timer data response for the given tournament.
     *
     * <p>Loads tournament, phases, matches, phase breaks, computes timeline, and
     * assembles audio URLs into a single {@link TimerDataResponse}.
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant per DEC-5)
     * @return the assembled timer data response; never {@code null}
     * @throws InvalidTimerUrlException   if the tournament does not exist for the active tenant (AC7)
     * @throws NoActiveTournamentException if the tournament is in DRAFT or CANCELLED status (AC7)
     */
    public TimerDataResponse buildTimerData(UUID tournamentId) {
        // ── Step 1: Load and validate tournament ─────────────────────────────

        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new InvalidTimerUrlException(tournamentId));

        // AC7: Only PLANNED, ACTIVE, COMPLETED are accessible via the timer
        if (!TIMER_ACCESSIBLE_STATUSES.contains(tournament.getStatus())) {
            throw new NoActiveTournamentException(tournamentId, tournament.getStatus());
        }

        // ── Step 2: Load phases ───────────────────────────────────────────────

        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        phases.sort(java.util.Comparator.comparingInt(Phase::getSequenceNumber));

        // AC7: no phases configured → 200 with emptySchedule=true
        if (phases.isEmpty()) {
            return buildEmptyScheduleResponse(tournament);
        }

        // ── Step 3: Load matches (for lap counts) and phase breaks ────────────

        List<Match> allMatches = new ArrayList<>();
        List<PhaseBreak> allBreaks = new ArrayList<>();
        int[] maxLapByPhaseIndex = new int[phases.size()];

        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            List<Match> phaseMatches = matchRepository.findByPhaseId(phase.getId());
            int maxLap = 0;
            for (Match m : phaseMatches) {
                if (m.getLapNumber() != null && m.getLapNumber() > maxLap) {
                    maxLap = m.getLapNumber();
                }
            }
            maxLapByPhaseIndex[i] = maxLap;
            allMatches.addAll(phaseMatches);
            allBreaks.addAll(phaseBreakRepository.findByPhaseId(phase.getId()));
        }

        // ── Step 4: Build PhaseConfigs for timeline ───────────────────────────

        List<PhaseConfig> phaseConfigs = buildPhaseConfigs(phases, allBreaks, maxLapByPhaseIndex);

        // ── Step 5: Compute timeline ──────────────────────────────────────────

        LocalTime startTime = tournament.getPlannedStartTime();
        boolean hasStartTime = (startTime != null);
        List<TimelineEntry> timeline = hasStartTime
                ? timelineCalculationService.calculate(startTime, phaseConfigs, 0)
                : Collections.emptyList();

        // ── Step 6: Map timeline entries → schedule entries ───────────────────

        List<TimerScheduleEntryResponse> schedule = buildSchedule(timeline, hasStartTime,
                phases, allBreaks, maxLapByPhaseIndex);

        // ── Step 7: Build phase summaries ─────────────────────────────────────

        List<TimerPhaseResponse> phaseSummaries = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            phaseSummaries.add(new TimerPhaseResponse(
                    phase.getSequenceNumber(),
                    phase.getDescription(),
                    phase.getStatus(),
                    maxLapByPhaseIndex[i]
            ));
        }

        // ── Step 8: Determine current position (AC5) ──────────────────────────

        int currentPhaseNumber = 0;
        int currentLapNumber = 0;
        for (Phase phase : phases) {
            if ("ACTIVE".equals(phase.getStatus())) {
                currentPhaseNumber = phase.getSequenceNumber();
                currentLapNumber = phase.getCurrentLapNumber();
                break;
            }
        }

        // ── Step 9: Build audio URLs (AC4) ────────────────────────────────────

        TimerAudioResponse audio = buildAudioResponse(tournamentId);

        // ── Step 10: Assemble response ────────────────────────────────────────

        TimerDataResponse response = new TimerDataResponse();
        response.setTournamentName(tournament.getDescription());
        response.setTournamentId(tournamentId);
        response.setTenantId(tournament.getTenantId());
        response.setTournamentStatus(tournament.getStatus());
        response.setCurrentPhaseNumber(currentPhaseNumber);
        response.setCurrentLapNumber(currentLapNumber);
        response.setHasStartTime(hasStartTime);
        response.setEmptySchedule(false);
        response.setSchedule(schedule);
        response.setPhases(phaseSummaries);
        response.setAudio(audio);
        return response;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a response for the "no phases" case (AC7).
     */
    private TimerDataResponse buildEmptyScheduleResponse(Tournament tournament) {
        TimerDataResponse response = new TimerDataResponse();
        response.setTournamentName(tournament.getDescription());
        response.setTournamentId(tournament.getId());
        response.setTenantId(tournament.getTenantId());
        response.setTournamentStatus(tournament.getStatus());
        response.setCurrentPhaseNumber(0);
        response.setCurrentLapNumber(0);
        response.setHasStartTime(tournament.getPlannedStartTime() != null);
        response.setEmptySchedule(true);
        response.setSchedule(Collections.emptyList());
        response.setPhases(Collections.emptyList());
        response.setAudio(buildAudioResponse(tournament.getId()));
        return response;
    }

    /**
     * Builds the ordered schedule by either mapping the computed timeline (when startTime is set)
     * or constructing bare round entries from match data (when startTime is absent).
     */
    private List<TimerScheduleEntryResponse> buildSchedule(List<TimelineEntry> timeline,
                                                             boolean hasStartTime,
                                                             List<Phase> phases,
                                                             List<PhaseBreak> allBreaks,
                                                             int[] maxLapByPhaseIndex) {
        if (hasStartTime) {
            return mapTimelineToSchedule(timeline);
        } else {
            return buildBareSchedule(phases, allBreaks, maxLapByPhaseIndex);
        }
    }

    /**
     * Maps computed timeline entries to schedule entries (when startTime is set).
     * AC3: break type mapping from TimelineEntryType.
     */
    private List<TimerScheduleEntryResponse> mapTimelineToSchedule(List<TimelineEntry> timeline) {
        List<TimerScheduleEntryResponse> schedule = new ArrayList<>();
        for (TimelineEntry entry : timeline) {
            TimelineEntryType type = entry.type();
            String start = formatTime(entry.startTime());
            String end = formatTime(entry.endTime());

            switch (type) {
                case MATCH_ROUND -> {
                    if (entry.lapNumber() > 0) {
                        schedule.add(TimerScheduleEntryResponse.round(
                                entry.phaseNumber(), entry.lapNumber(), start, end));
                    }
                    // lapNumber == 0 → zero-lap phase marker, skip
                }
                case LAP_BREAK ->
                    // AC3: LAP_BREAK → REGULAR
                    schedule.add(TimerScheduleEntryResponse.breakEntry(
                            TimerBreakType.REGULAR.name(), null, start, end));
                case INTRA_PHASE_BREAK ->
                    // AC3: INTRA_PHASE_BREAK → ADDITIONAL
                    schedule.add(TimerScheduleEntryResponse.breakEntry(
                            TimerBreakType.ADDITIONAL.name(), entry.label(), start, end));
                case SECTION_BREAK ->
                    // AC3: SECTION_BREAK → ADDITIONAL
                    schedule.add(TimerScheduleEntryResponse.breakEntry(
                            TimerBreakType.ADDITIONAL.name(), null, start, end));
            }
        }
        return schedule;
    }

    /**
     * Builds a bare schedule (no wall-clock times) when no startTime is set.
     * Creates round entries from match-derived lap counts; intra-phase breaks are included
     * as ADDITIONAL breaks; no LAP_BREAK or SECTION_BREAK entries (no timing info).
     */
    private List<TimerScheduleEntryResponse> buildBareSchedule(List<Phase> phases,
                                                                 List<PhaseBreak> allBreaks,
                                                                 int[] maxLapByPhaseIndex) {
        // Build lookup: phaseId → sorted list of PhaseBreaks
        java.util.Map<UUID, List<PhaseBreak>> breaksByPhase = new java.util.HashMap<>();
        for (PhaseBreak pb : allBreaks) {
            breaksByPhase.computeIfAbsent(pb.getPhaseId(), k -> new ArrayList<>()).add(pb);
        }

        List<TimerScheduleEntryResponse> schedule = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            int maxLap = maxLapByPhaseIndex[i];
            List<PhaseBreak> breaks = breaksByPhase.getOrDefault(phase.getId(), Collections.emptyList());
            breaks.sort(java.util.Comparator.comparingInt(PhaseBreak::getAfterLapNumber));

            // Build lookup: afterLapNumber → PhaseBreak
            java.util.Map<Integer, PhaseBreak> breakByLap = new java.util.HashMap<>();
            for (PhaseBreak pb : breaks) {
                breakByLap.put(pb.getAfterLapNumber(), pb);
            }

            for (int lap = 1; lap <= maxLap; lap++) {
                schedule.add(TimerScheduleEntryResponse.round(
                        phase.getSequenceNumber(), lap, null, null));
                // After each lap except the last: insert break if present
                if (lap < maxLap) {
                    PhaseBreak pb = breakByLap.get(lap);
                    if (pb != null) {
                        schedule.add(TimerScheduleEntryResponse.breakEntry(
                                TimerBreakType.ADDITIONAL.name(), pb.getLabel(), null, null));
                    }
                    // No LAP_BREAK entries without timing info — they have no semantic content
                }
            }
        }
        return schedule;
    }

    /**
     * Builds {@link PhaseConfig} objects from phase data.
     * Uses DEFAULT_LAP_TIME_MINUTES and DEFAULT_LAP_BREAK_MINUTES for all phases.
     */
    private List<PhaseConfig> buildPhaseConfigs(List<Phase> phases,
                                                 List<PhaseBreak> allBreaks,
                                                 int[] maxLapByPhaseIndex) {
        // Build lookup: phaseId → PhaseBreaks
        java.util.Map<UUID, List<PhaseBreak>> breaksByPhase = new java.util.HashMap<>();
        for (PhaseBreak pb : allBreaks) {
            breaksByPhase.computeIfAbsent(pb.getPhaseId(), k -> new ArrayList<>()).add(pb);
        }

        List<PhaseConfig> configs = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            int lapCount = maxLapByPhaseIndex[i];
            List<PhaseBreak> phaseBreaks = breaksByPhase.getOrDefault(phase.getId(), Collections.emptyList());
            List<PhaseBreakConfig> breakConfigs = new ArrayList<>();
            for (PhaseBreak pb : phaseBreaks) {
                breakConfigs.add(new PhaseBreakConfig(
                        pb.getAfterLapNumber(), pb.getDurationMinutes(), pb.getLabel()));
            }
            configs.add(new PhaseConfig(
                    phase.getSequenceNumber(),
                    lapCount,
                    DEFAULT_LAP_TIME_MINUTES,
                    DEFAULT_LAP_BREAK_MINUTES,
                    breakConfigs));
        }
        return configs;
    }

    /**
     * Builds the audio response by checking which categories have files uploaded (AC4).
     *
     * <p>Uses {@link AudioStorageService#stream} to check presence and immediately closes
     * the stream to avoid resource leaks. A non-empty optional means a file exists.
     */
    private TimerAudioResponse buildAudioResponse(UUID tournamentId) {
        String startUrl = buildAudioUrl(tournamentId, AudioCategory.START);
        String endUrl = buildAudioUrl(tournamentId, AudioCategory.END);
        String pauseUrl = buildAudioUrl(tournamentId, AudioCategory.PAUSE);
        return new TimerAudioResponse(startUrl, endUrl, pauseUrl);
    }

    /**
     * Returns the audio streaming URL for the given category, or {@code null} if no file
     * has been uploaded for that category.
     *
     * <p>The URL path is deterministic and stable per AC note: same tournament + category
     * always produces the same URL (E11S02 stability requirement).
     */
    private String buildAudioUrl(UUID tournamentId, AudioCategory category) {
        try {
            Optional<InputStream> maybeStream = audioStorageService.stream(tournamentId, category);
            if (maybeStream.isPresent()) {
                try (InputStream ignored = maybeStream.get()) {
                    // Close immediately — we only need to know the file exists (AC4)
                } catch (java.io.IOException ioEx) {
                    // If close fails the file is still considered present — URL remains valid
                }
                return "/api/tournaments/" + tournamentId + "/audio/"
                        + category.name() + "/stream";
            }
        } catch (java.util.NoSuchElementException ignored) {
            // Tournament not found in audio service — treat as no file
        }
        return null;
    }

    /** Formats a {@link LocalTime} as {@code "HH:mm"}, or returns {@code null} for {@code null} input. */
    private String formatTime(LocalTime time) {
        return time != null ? time.format(TIME_FMT) : null;
    }
}
