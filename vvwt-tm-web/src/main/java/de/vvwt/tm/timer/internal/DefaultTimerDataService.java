// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.timer.InvalidTimerUrlException;
import de.vvwt.tm.timer.NoActiveTournamentException;
import de.vvwt.tm.timer.NoScheduleConfiguredException;
import de.vvwt.tm.timer.TimerAudioResponse;
import de.vvwt.tm.timer.TimerBreakType;
import de.vvwt.tm.timer.TimerDataResponse;
import de.vvwt.tm.timer.TimerDataService;
import de.vvwt.tm.timer.TimerPhaseResponse;
import de.vvwt.tm.timer.TimerScheduleEntryResponse;
import de.vvwt.tm.timer.audio.AudioCategory;
import de.vvwt.tm.timer.audio.AudioStorageService;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakConfig;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseConfig;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.TimelineEntry;
import de.vvwt.tm.tournament.TimelineEntryType;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.io.InputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical implementation of {@link TimerDataService} for the {@code timer} bounded context.
 *
 * <p>Assembles the timer data response for a given tournament from multiple repositories and
 * services. Canonical FQN: {@code de.vvwt.tm.timer.internal.DefaultTimerDataService} per DEC-35
 * (implementation in {@code .internal}; naming canon {@code Default*Service}).
 *
 * <h2>E11S10 — Timeline math from operator-configured DraftConfig (AC5–AC8, AC11, AC12)</h2>
 *
 * <p>This implementation loads the operator-configured {@link DraftConfig} from {@link
 * Tournament#getDraftJson()} and uses per-section {@link DraftSection#getLapTimeMinutes()}, {@link
 * DraftSection#getLapBreakTimeMinutes()}, and {@link DraftSection#getSectionBreakTimeMinutes()}
 * values instead of the previously hardcoded {@code DEFAULT_LAP_TIME_MINUTES=15} / {@code
 * DEFAULT_LAP_BREAK_MINUTES=5} constants.
 *
 * <p>Strategy-i (per-phase loop): calls {@link TimelineCalculationService#calculate} once per
 * phase with {@code sectionBreakMinutes=0}, then manually appends {@link
 * TimelineEntryType#SECTION_BREAK} entries between consecutive phases using each section's own
 * {@link DraftSection#getSectionBreakTimeMinutes()} value. This mirrors {@code
 * DefaultDraftService.buildTimeline} without creating a shared interface crossing module boundaries
 * (DEC-35/DEC-58). Delivery option (b) per Story Notes §Timeline-math-architecture.
 *
 * <p>Error handling (AC11, AC12): when {@code tournament.draftJson} is absent/null, or when any
 * configured {@code lapTimeMinutes ≤ 0} for a non-zero-lap phase, or when the phase count and
 * section count are inconsistent, throws {@link NoScheduleConfiguredException} — consistent with
 * E11S03 error states.
 *
 * <h2>Break type mapping (AC3)</h2>
 *
 * <ul>
 *   <li>{@link TimelineEntryType#LAP_BREAK} → {@link TimerBreakType#REGULAR}
 *   <li>{@link TimelineEntryType#INTRA_PHASE_BREAK} → {@link TimerBreakType#ADDITIONAL}
 *   <li>{@link TimelineEntryType#SECTION_BREAK} → {@link TimerBreakType#ADDITIONAL}
 * </ul>
 *
 * <h2>Wave-2 audio URL construction (AC-AUDIO-URL-CONSTRUCTION-WAVE2)</h2>
 *
 * <p>URL pattern: {@code /api/audio/tournaments/{tournamentId}/{category}/stream}.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22: REFACTOR Phase-3 on TDD-authored {@code DefaultTimerDataServiceTest} (AC14)
 *   <li>DEC-35: interface in public package; impl in {@code .internal}
 *   <li>DEC-36: cross-package consumers reference {@code TimerDataService}, not this class
 *   <li>DEC-54: {@code mvn verify} exit-zero is the QA gate
 *   <li>DEC-76: SPDX header present
 * </ul>
 *
 * @see TimerDataService
 * @see <a href="contexts/artefacts/stories/E11S10.story.md">Story E11S10</a>
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
@Service
public class DefaultTimerDataService implements TimerDataService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTimerDataService.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Tournament statuses that are accessible via the timer (PLANNED, ACTIVE, COMPLETED). */
    private static final Set<String> TIMER_ACCESSIBLE_STATUSES =
            Set.of("PLANNED", "ACTIVE", "COMPLETED");

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final PhaseBreakRepository phaseBreakRepository;
    private final TimelineCalculationService timelineCalculationService;
    private final AudioStorageService audioStorageService;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    /**
     * Constructor. {@link ObjectMapper} added (E11S10) for DraftConfig deserialization.
     *
     * <p>All tournament repos and {@link TimelineCalculationService} are from {@code
     * de.vvwt.tm.tournament.*} root. {@link AudioStorageService} is from {@code
     * de.vvwt.tm.timer.audio} (sub-package of this module per option A).
     */
    public DefaultTimerDataService(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            PhaseBreakRepository phaseBreakRepository,
            TimelineCalculationService timelineCalculationService,
            AudioStorageService audioStorageService,
            TenantContext tenantContext,
            ObjectMapper objectMapper) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.phaseBreakRepository = phaseBreakRepository;
        this.timelineCalculationService = timelineCalculationService;
        this.audioStorageService = audioStorageService;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds the timer data response for the given tournament.
     *
     * <p>Loads tournament, phases, matches, phase breaks, DraftConfig, computes timeline, and
     * assembles audio URLs into a single {@link TimerDataResponse}.
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant per DEC-5)
     * @return the assembled timer data response; never {@code null}
     * @throws InvalidTimerUrlException if the tournament does not exist for the active tenant
     * @throws NoActiveTournamentException if the tournament is in DRAFT or CANCELLED status
     * @throws NoScheduleConfiguredException if draftJson is absent or contains invalid values
     *     (AC11, AC12)
     */
    @Override
    @Transactional(readOnly = true)
    public TimerDataResponse buildTimerData(UUID tournamentId) {
        // ── Step 1: Load and validate tournament ─────────────────────────────

        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new InvalidTimerUrlException(tournamentId));

        // AC-TIMER-ACCESSIBLE-STATUSES-INVARIANT: Only PLANNED, ACTIVE, COMPLETED accessible
        if (!TIMER_ACCESSIBLE_STATUSES.contains(tournament.getStatus())) {
            throw new NoActiveTournamentException(tournamentId, tournament.getStatus());
        }

        // ── Step 2: Load phases ───────────────────────────────────────────────

        List<Phase> phases = new ArrayList<>(phaseRepository.findByTournamentId(tournamentId));
        phases.sort(java.util.Comparator.comparingInt(Phase::getSequenceNumber));

        // AC-BUILD-TIMER-DATA-EMPTY-SCHEDULE: no phases → 200 with emptySchedule=true
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

        // ── Step 4: Load DraftConfig (AC5–AC8, AC11, AC12) ───────────────────

        DraftConfig draftConfig = loadDraftConfig(tournament);

        // ── Step 5: Build PhaseConfigs for timeline ───────────────────────────

        List<PhaseConfig> phaseConfigs =
                buildPhaseConfigs(phases, allBreaks, maxLapByPhaseIndex, draftConfig);

        // ── Step 6: Compute timeline using Strategy-i ─────────────────────────

        LocalTime startTime = tournament.getPlannedStartTime();
        boolean hasStartTime = (startTime != null);
        List<TimelineEntry> timeline =
                hasStartTime
                        ? buildTimelineStrategyI(startTime, phaseConfigs, draftConfig)
                        : Collections.emptyList();

        // ── Step 7: Map timeline entries → schedule entries ───────────────────

        List<TimerScheduleEntryResponse> schedule =
                buildSchedule(timeline, hasStartTime, phases, allBreaks, maxLapByPhaseIndex);

        // ── Step 8: Build phase summaries ─────────────────────────────────────

        List<TimerPhaseResponse> phaseSummaries = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            phaseSummaries.add(
                    new TimerPhaseResponse(
                            phase.getSequenceNumber(),
                            phase.getDescription(),
                            phase.getStatus(),
                            maxLapByPhaseIndex[i]));
        }

        // ── Step 9: Determine current position (AC5) ──────────────────────────

        int currentPhaseNumber = 0;
        int currentLapNumber = 0;
        for (Phase phase : phases) {
            if ("ACTIVE".equals(phase.getStatus())) {
                currentPhaseNumber = phase.getSequenceNumber();
                currentLapNumber = phase.getCurrentLapNumber();
                break;
            }
        }

        // ── Step 10: Build audio URLs (AC4) ───────────────────────────────────

        TimerAudioResponse audio = buildAudioResponse(tournamentId);

        // ── Step 11: Assemble response ────────────────────────────────────────

        TimerDataResponse response = new TimerDataResponse();
        response.setTournamentName(tournament.getDescription());
        response.setTournamentId(tournamentId);
        response.setTenantId(tenantContext.current());
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

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Loads the {@link DraftConfig} from {@link Tournament#getDraftJson()}.
     *
     * <p>AC11: when {@code draftJson} is absent/null → throws {@link
     * NoScheduleConfiguredException}.
     *
     * @param tournament the tournament entity
     * @return deserialized DraftConfig; never {@code null}
     * @throws NoScheduleConfiguredException if draftJson is absent/null or cannot be parsed (AC11)
     */
    private DraftConfig loadDraftConfig(Tournament tournament) {
        String json = tournament.getDraftJson();
        if (json == null || json.isBlank()) {
            // AC11: no draft configured → consistent error state
            log.warn(
                    "Timer data requested for tournament {} but draftJson is absent"
                            + " — no schedule can be computed (AC11)",
                    tournament.getId());
            throw new NoScheduleConfiguredException(tournament.getId());
        }
        try {
            return objectMapper.readValue(json, DraftConfig.class);
        } catch (Exception e) {
            log.warn(
                    "Timer data: failed to parse draftJson for tournament {} — {}",
                    tournament.getId(),
                    e.getMessage());
            throw new NoScheduleConfiguredException(tournament.getId());
        }
    }

    /** Builds a response for the "no phases" case (AC-BUILD-TIMER-DATA-EMPTY-SCHEDULE). */
    private TimerDataResponse buildEmptyScheduleResponse(Tournament tournament) {
        TimerAudioResponse audio = buildAudioResponse(tournament.getId());
        TimerDataResponse response = new TimerDataResponse();
        response.setTournamentName(tournament.getDescription());
        response.setTournamentId(tournament.getId());
        response.setTenantId(tenantContext.current());
        response.setTournamentStatus(tournament.getStatus());
        response.setCurrentPhaseNumber(0);
        response.setCurrentLapNumber(0);
        response.setHasStartTime(tournament.getPlannedStartTime() != null);
        response.setEmptySchedule(true);
        response.setSchedule(Collections.emptyList());
        response.setPhases(Collections.emptyList());
        response.setAudio(audio);
        return response;
    }

    /**
     * Builds the ordered schedule by either mapping the computed timeline (when startTime is set)
     * or constructing bare round entries from match data (when startTime is absent).
     */
    private List<TimerScheduleEntryResponse> buildSchedule(
            List<TimelineEntry> timeline,
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
     *
     * <p>AC-BREAK-TYPE-MAPPING: break type mapping from TimelineEntryType: LAP_BREAK→REGULAR,
     * INTRA_PHASE_BREAK→ADDITIONAL, SECTION_BREAK→ADDITIONAL.
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
                        schedule.add(
                                TimerScheduleEntryResponse.round(
                                        entry.phaseNumber(), entry.lapNumber(), start, end));
                    }
                    // lapNumber == 0 → zero-lap phase marker, skip
                }
                case LAP_BREAK ->
                        // AC-BREAK-TYPE-MAPPING: LAP_BREAK → REGULAR
                        schedule.add(
                                TimerScheduleEntryResponse.breakEntry(
                                        TimerBreakType.REGULAR.name(), null, start, end));
                case INTRA_PHASE_BREAK ->
                        // AC-BREAK-TYPE-MAPPING: INTRA_PHASE_BREAK → ADDITIONAL
                        schedule.add(
                                TimerScheduleEntryResponse.breakEntry(
                                        TimerBreakType.ADDITIONAL.name(),
                                        entry.label(),
                                        start,
                                        end));
                case SECTION_BREAK ->
                        // AC-BREAK-TYPE-MAPPING: SECTION_BREAK → ADDITIONAL
                        schedule.add(
                                TimerScheduleEntryResponse.breakEntry(
                                        TimerBreakType.ADDITIONAL.name(), null, start, end));
            }
        }
        return schedule;
    }

    /**
     * Builds a bare schedule (no wall-clock times) when no startTime is set. Creates round entries
     * from match-derived lap counts; intra-phase breaks are included as ADDITIONAL breaks.
     */
    private List<TimerScheduleEntryResponse> buildBareSchedule(
            List<Phase> phases, List<PhaseBreak> allBreaks, int[] maxLapByPhaseIndex) {
        // Build lookup: phaseId → sorted list of PhaseBreaks
        Map<UUID, List<PhaseBreak>> breaksByPhase = new HashMap<>();
        for (PhaseBreak pb : allBreaks) {
            breaksByPhase.computeIfAbsent(pb.getPhaseId(), k -> new ArrayList<>()).add(pb);
        }

        List<TimerScheduleEntryResponse> schedule = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            int maxLap = maxLapByPhaseIndex[i];
            List<PhaseBreak> breaks =
                    breaksByPhase.getOrDefault(phase.getId(), Collections.emptyList());
            breaks.sort(java.util.Comparator.comparingInt(PhaseBreak::getAfterLapNumber));

            // Build lookup: afterLapNumber → PhaseBreak
            Map<Integer, PhaseBreak> breakByLap = new HashMap<>();
            for (PhaseBreak pb : breaks) {
                breakByLap.put(pb.getAfterLapNumber(), pb);
            }

            for (int lap = 1; lap <= maxLap; lap++) {
                schedule.add(
                        TimerScheduleEntryResponse.round(
                                phase.getSequenceNumber(), lap, null, null));
                // After each lap except the last: insert break if present
                if (lap < maxLap) {
                    PhaseBreak pb = breakByLap.get(lap);
                    if (pb != null) {
                        schedule.add(
                                TimerScheduleEntryResponse.breakEntry(
                                        TimerBreakType.ADDITIONAL.name(),
                                        pb.getLabel(),
                                        null,
                                        null));
                    }
                }
            }
        }
        return schedule;
    }

    /**
     * Builds {@link PhaseConfig} objects for timeline calculation using operator-configured
     * DraftConfig values (AC5, AC6, AC8).
     *
     * <p>E11S10: uses {@link DraftSection#getLapTimeMinutes()} and {@link
     * DraftSection#getLapBreakTimeMinutes()} from the operator-configured draft instead of the
     * previously hardcoded {@code DEFAULT_LAP_TIME_MINUTES=15} / {@code DEFAULT_LAP_BREAK_MINUTES=5}
     * constants.
     *
     * <p>Phase-to-section matching: phases sorted by sequenceNumber, sections sorted by
     * sectionNumber. Sections are matched by index. If draftConfig has fewer sections than phases,
     * throws {@link NoScheduleConfiguredException} (AC12).
     *
     * <p>AC12: if any matched section has {@code lapTimeMinutes ≤ 0} for a phase with {@code
     * lapCount > 0}, throws {@link NoScheduleConfiguredException}.
     *
     * @param phases sorted phases (by sequenceNumber)
     * @param allBreaks all phase breaks
     * @param maxLapByPhaseIndex max lap number per phase index
     * @param draftConfig the operator-configured draft
     * @return list of PhaseConfig in phase order
     */
    private List<PhaseConfig> buildPhaseConfigs(
            List<Phase> phases,
            List<PhaseBreak> allBreaks,
            int[] maxLapByPhaseIndex,
            DraftConfig draftConfig) {
        // Build lookup: phaseId → PhaseBreaks
        Map<UUID, List<PhaseBreak>> breaksByPhase = new HashMap<>();
        for (PhaseBreak pb : allBreaks) {
            breaksByPhase.computeIfAbsent(pb.getPhaseId(), k -> new ArrayList<>()).add(pb);
        }

        // Sort sections by sectionNumber (ascending)
        List<DraftSection> sections = new ArrayList<>(draftConfig.getSections());
        sections.sort(java.util.Comparator.comparingInt(DraftSection::getSectionNumber));

        List<PhaseConfig> configs = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            Phase phase = phases.get(i);
            int lapCount = maxLapByPhaseIndex[i];

            // AC12: If we have fewer sections than phases, the draft is inconsistent
            if (i >= sections.size()) {
                log.warn(
                        "Timer data: phase {} (sequenceNumber={}) has no matching DraftSection"
                                + " — draftConfig has {} sections but {} phases (AC12)",
                        phase.getId(),
                        phase.getSequenceNumber(),
                        sections.size(),
                        phases.size());
                throw new NoScheduleConfiguredException(phase.getTournamentId());
            }

            DraftSection section = sections.get(i);

            // AC12: lapTimeMinutes must be > 0 for phases with actual laps
            if (lapCount > 0 && section.getLapTimeMinutes() <= 0) {
                log.warn(
                        "Timer data: section {} has lapTimeMinutes={} <= 0 for phase with"
                                + " lapCount={} — invalid DraftConfig (AC12)",
                        section.getSectionNumber(),
                        section.getLapTimeMinutes(),
                        lapCount);
                throw new NoScheduleConfiguredException(phase.getTournamentId());
            }

            List<PhaseBreak> phaseBreaks =
                    breaksByPhase.getOrDefault(phase.getId(), Collections.emptyList());
            List<PhaseBreakConfig> breakConfigs = new ArrayList<>();
            for (PhaseBreak pb : phaseBreaks) {
                breakConfigs.add(
                        new PhaseBreakConfig(
                                pb.getAfterLapNumber(), pb.getDurationMinutes(), pb.getLabel()));
            }

            // Use operator-configured values (AC5, AC6) — NOT hardcoded defaults.
            // lapTimeMinutes: from DraftSection (AC5).
            // lapBreakMinutes: from DraftSection (AC6).
            // AC8 regression guard: phaseBreaks already contain intra-phase break configs.
            // For lapCount=0 (e.g., Siegerehrung), lapTimeMinutes is ignored by the engine but
            // PhaseConfig requires lapTimeMinutes > 0 when lapCount > 0; use section value (>0
            // per DraftSection validation) when lapCount>0, else use 1 as a safe sentinel.
            int lapTime = lapCount > 0 ? section.getLapTimeMinutes() : 1;
            configs.add(
                    new PhaseConfig(
                            phase.getSequenceNumber(),
                            lapCount,
                            lapTime,
                            section.getLapBreakTimeMinutes(),
                            breakConfigs));
        }
        return configs;
    }

    /**
     * Computes the timeline using Strategy-i: per-phase calls to {@link
     * TimelineCalculationService#calculate} with {@code sectionBreakMinutes=0}, then manually
     * appends {@link TimelineEntryType#SECTION_BREAK} entries between consecutive phases using each
     * section's own {@link DraftSection#getSectionBreakTimeMinutes()} (AC7).
     *
     * <p>This mirrors {@code DefaultDraftService.buildTimeline} (Strategy-i) without creating a
     * shared cross-module interface. Delivery option (b) per Story Notes §Timeline-math-architecture.
     *
     * @param startTime tournament start time (must not be null)
     * @param phaseConfigs matching PhaseConfig list
     * @param draftConfig the operator-configured draft (for sectionBreakTimeMinutes)
     * @return ordered timeline entries including SECTION_BREAK entries between phases
     */
    private List<TimelineEntry> buildTimelineStrategyI(
            LocalTime startTime, List<PhaseConfig> phaseConfigs, DraftConfig draftConfig) {
        List<DraftSection> sections = new ArrayList<>(draftConfig.getSections());
        sections.sort(java.util.Comparator.comparingInt(DraftSection::getSectionNumber));

        List<TimelineEntry> result = new ArrayList<>();
        LocalTime cursor = startTime;

        for (int i = 0; i < phaseConfigs.size(); i++) {
            PhaseConfig phaseConfig = phaseConfigs.get(i);
            boolean isLastPhase = (i == phaseConfigs.size() - 1);

            // Call calculate() per phase with sectionBreakMinutes=0 (Strategy-i)
            List<TimelineEntry> phaseEntries =
                    timelineCalculationService.calculate(cursor, List.of(phaseConfig), 0);
            result.addAll(phaseEntries);

            // Advance cursor to end of last entry in this phase (if any)
            if (!phaseEntries.isEmpty()) {
                cursor = phaseEntries.get(phaseEntries.size() - 1).endTime();
            }

            // AC7: Append SECTION_BREAK entry between consecutive phases
            if (!isLastPhase && i < sections.size()) {
                DraftSection section = sections.get(i);
                int sectionBreakMinutes = section.getSectionBreakTimeMinutes();
                if (sectionBreakMinutes > 0) {
                    LocalTime sectionBreakEnd = cursor.plusMinutes(sectionBreakMinutes);
                    result.add(
                            new TimelineEntry(
                                    phaseConfig.phaseNumber(),
                                    0,
                                    TimelineEntryType.SECTION_BREAK,
                                    cursor,
                                    sectionBreakEnd,
                                    null));
                    cursor = sectionBreakEnd;
                }
            }
        }

        return result;
    }

    /** Builds the audio response by checking which categories have files uploaded (AC4). */
    private TimerAudioResponse buildAudioResponse(UUID tournamentId) {
        String startUrl = buildAudioUrl(tournamentId, AudioCategory.START);
        String endUrl = buildAudioUrl(tournamentId, AudioCategory.END);
        String pauseUrl = buildAudioUrl(tournamentId, AudioCategory.PAUSE);
        return new TimerAudioResponse(startUrl, endUrl, pauseUrl);
    }

    /**
     * Returns the Wave-2-aligned audio streaming URL for the given category, or {@code null} if no
     * file has been uploaded.
     *
     * <p>AC-AUDIO-URL-CONSTRUCTION-WAVE2: URL pattern is {@code
     * /api/audio/tournaments/{tournamentId}/{category}/stream} (Wave-2-aligned).
     *
     * <p>@SuppressWarnings("try"): the try-with-resources block intentionally opens and immediately
     * closes the stream to verify real file access (DEC-29).
     */
    @SuppressWarnings("try")
    private String buildAudioUrl(UUID tournamentId, AudioCategory category) {
        try {
            Optional<InputStream> maybeStream = audioStorageService.stream(tournamentId, category);
            if (maybeStream.isPresent()) {
                try (InputStream ignored = maybeStream.get()) {
                    // Close immediately — we only need to know the file exists (AC4)
                } catch (java.io.IOException ioEx) {
                    // If close fails the file is still considered present — URL remains valid
                }
                // Wave-2-aligned URL per AC-AUDIO-URL-CONSTRUCTION-WAVE2
                return "/api/audio/tournaments/" + tournamentId + "/" + category.name() + "/stream";
            }
        } catch (java.util.NoSuchElementException ignored) {
            // Tournament not found in audio service — treat as no file
        }
        return null;
    }

    /**
     * Formats a {@link LocalTime} as {@code "HH:mm"}, or returns {@code null} for {@code null}
     * input.
     */
    private String formatTime(LocalTime time) {
        return time != null ? time.format(TIME_FMT) : null;
    }
}
