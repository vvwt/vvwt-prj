package de.vvwt.tm.infrastructure.print;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhaseBreak;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.activity.ActivityAssignment;
import de.vvwt.tm.domain.activity.ActivityAssignmentResult;
import de.vvwt.tm.domain.activity.ActivityAssignmentService;
import de.vvwt.tm.domain.timeline.PhaseBreakConfig;
import de.vvwt.tm.domain.timeline.PhaseConfig;
import de.vvwt.tm.domain.timeline.TimelineCalculationService;
import de.vvwt.tm.domain.timeline.TimelineEntry;
import de.vvwt.tm.domain.timeline.TimelineEntryType;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles per-team {@link LaufzettelRow} lists for the Laufzettel print template.
 *
 * <p>This service joins data from four sources — match schedule, referee assignments, activity
 * assignments, and timeline — into a flat list of display rows per team. It has no database access:
 * all inputs are pre-loaded by {@link PrintController}.
 *
 * <h2>Round state priority (AC6 — E08S08)</h2>
 *
 * <p>Each round yields exactly one row per team, determined by priority: PLAYING &gt; REFEREEING
 * &gt; ACTIVITY &gt; FREE. By E03S10 / E08S04 design, PLAYING and REFEREEING are mutually exclusive
 * (a team refereeing cannot be playing), and ACTIVITY is only assigned to free rounds — so the
 * priority is a safety-net against future rule violations, not a common case.
 *
 * <h2>Break row rendering (AC9)</h2>
 *
 * <p>LAP_BREAK entries are skipped (implicit gap — not shown on Laufzettel). INTRA_PHASE_BREAK and
 * SECTION_BREAK entries become break separator rows.
 *
 * <h2>No-start-time path (AC10)</h2>
 *
 * <p>If {@code tournament.getPlannedStartTime()} is null, the timeline is empty and all time
 * windows default to {@code ""}. The outer model's {@code hasTime} flag is set accordingly.
 *
 * <h2>Multi-phase support (AC11)</h2>
 *
 * <p>Phases are processed in {@code sequenceNumber} order. A phase header row is inserted before
 * each phase's rounds. Section breaks appear between phases as break separator rows.
 *
 * @see LaufzettelRow
 * @see PrintController
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S08.story.md">Story
 *     E08S08</a>
 */
@Service
public class LaufzettelAssembler {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Default lap time in minutes used when no explicit phase configuration is provided. Used only
     * as a fallback for timeline display when E08S05 draft config is not yet available.
     */
    static final int DEFAULT_LAP_TIME_MINUTES = 15;

    /** Default lap break time in minutes used when no explicit phase configuration is provided. */
    static final int DEFAULT_LAP_BREAK_MINUTES = 5;

    private final TimelineCalculationService timelineCalculationService;
    private final ActivityAssignmentService activityAssignmentService;

    public LaufzettelAssembler(
            TimelineCalculationService timelineCalculationService,
            ActivityAssignmentService activityAssignmentService) {
        this.timelineCalculationService = timelineCalculationService;
        this.activityAssignmentService = activityAssignmentService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Assembles a map of team UUID → list of Laufzettel rows for all teams.
     *
     * <p>Uses default lap time ({@value DEFAULT_LAP_TIME_MINUTES} min) and lap break ({@value
     * DEFAULT_LAP_BREAK_MINUTES} min) for timeline calculation. For accurate time windows, use
     * {@link #assembleWithPhaseConfig(Tournament, List, List, Map, Map, Map, List, int, Map, Map)}.
     *
     * @param tournament the tournament (for start time)
     * @param phases phases sorted by sequenceNumber ascending; must not be null
     * @param teams all participating teams; must not be null
     * @param avatarsByPhase phaseId → TeamAvatars; must not be null
     * @param matchesByPhase phaseId → Matches; must not be null
     * @param breaksByPhase phaseId → PhaseBreaks; must not be null
     * @param activityTypes activity types to assign; must not be null
     * @param sectionBreakMinutes section break between phases in minutes (≥ 0)
     * @return map of teamId → ordered list of {@link LaufzettelRow}; never null
     */
    public Map<UUID, List<LaufzettelRow>> assemble(
            Tournament tournament,
            List<Phase> phases,
            List<Team> teams,
            Map<UUID, List<TeamAvatar>> avatarsByPhase,
            Map<UUID, List<Match>> matchesByPhase,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            List<ActivityType> activityTypes,
            int sectionBreakMinutes) {
        return assembleWithPhaseConfig(
                tournament,
                phases,
                teams,
                avatarsByPhase,
                matchesByPhase,
                breaksByPhase,
                activityTypes,
                sectionBreakMinutes,
                Collections.emptyMap(),
                Collections.emptyMap());
    }

    /**
     * Assembles Laufzettel rows with explicit per-phase lap time and break time overrides.
     *
     * @param lapTimeByPhase phaseId → lap time in minutes; missing entries fall back to default
     * @param lapBreakByPhase phaseId → lap break time in minutes; missing entries fall back to
     *     default
     */
    public Map<UUID, List<LaufzettelRow>> assembleWithPhaseConfig(
            Tournament tournament,
            List<Phase> phases,
            List<Team> teams,
            Map<UUID, List<TeamAvatar>> avatarsByPhase,
            Map<UUID, List<Match>> matchesByPhase,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            List<ActivityType> activityTypes,
            int sectionBreakMinutes,
            Map<UUID, Integer> lapTimeByPhase,
            Map<UUID, Integer> lapBreakByPhase) {

        if (phases == null || phases.isEmpty() || teams == null || teams.isEmpty()) {
            return Collections.emptyMap();
        }

        // ── Step 1: Build core lookup structures ──────────────────────────────

        Map<UUID, Team> teamById = new HashMap<>();
        for (Team team : teams) {
            teamById.put(team.getId(), team);
        }

        // avatar UUID → team UUID (from TeamAvatar entities)
        Map<UUID, UUID> teamByAvatarId = new HashMap<>();
        for (List<TeamAvatar> avatarList : avatarsByPhase.values()) {
            for (TeamAvatar avatar : avatarList) {
                teamByAvatarId.put(avatar.getId(), avatar.getTeamId());
            }
        }

        // ── Step 2: Per-phase playing/referee/field lookups ───────────────────

        // phaseId → lapNumber → set of playing teamIds
        Map<UUID, Map<Integer, Set<UUID>>> playingByPhase = new HashMap<>();
        // phaseId → lapNumber → teamId → fieldNumber
        Map<UUID, Map<Integer, Map<UUID, Integer>>> fieldByPhase = new HashMap<>();
        // phaseId → lapNumber → teamId → opponent teamId
        Map<UUID, Map<Integer, Map<UUID, UUID>>> opponentByPhase = new HashMap<>();
        // phaseId → lapNumber → set of refereeing teamIds
        Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase = new HashMap<>();
        // phaseId → lapNumber → refereeTeamId → fieldNumber
        Map<UUID, Map<Integer, Map<UUID, Integer>>> refereeFieldByPhase = new HashMap<>();
        // phaseId → maxLapNumber (total lap count derived from matches)
        Map<UUID, Integer> maxLapByPhase = new HashMap<>();

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            List<Match> matches = matchesByPhase.getOrDefault(phaseId, Collections.emptyList());

            Map<Integer, Set<UUID>> playingByLap = new HashMap<>();
            Map<Integer, Map<UUID, Integer>> fieldByLap = new HashMap<>();
            Map<Integer, Map<UUID, UUID>> opponentByLap = new HashMap<>();
            Map<Integer, Set<UUID>> refereeByLap = new HashMap<>();
            Map<Integer, Map<UUID, Integer>> refereeFieldByLap = new HashMap<>();
            int maxLap = 0;

            for (Match match : matches) {
                if (match.getLapNumber() == null) continue;
                int lap = match.getLapNumber();
                if (lap > maxLap) maxLap = lap;

                UUID t1 = teamByAvatarId.get(match.getMemberAvatar1Id());
                UUID t2 = teamByAvatarId.get(match.getMemberAvatar2Id());
                if (t1 != null && t2 != null) {
                    playingByLap.computeIfAbsent(lap, k -> new HashSet<>()).add(t1);
                    playingByLap.computeIfAbsent(lap, k -> new HashSet<>()).add(t2);
                    opponentByLap.computeIfAbsent(lap, k -> new HashMap<>()).put(t1, t2);
                    opponentByLap.computeIfAbsent(lap, k -> new HashMap<>()).put(t2, t1);
                    if (match.getFieldNumber() != null) {
                        fieldByLap
                                .computeIfAbsent(lap, k -> new HashMap<>())
                                .put(t1, match.getFieldNumber());
                        fieldByLap
                                .computeIfAbsent(lap, k -> new HashMap<>())
                                .put(t2, match.getFieldNumber());
                    }
                }
                if (match.getRefereeTeamId() != null) {
                    UUID refId = match.getRefereeTeamId();
                    refereeByLap.computeIfAbsent(lap, k -> new HashSet<>()).add(refId);
                    if (match.getFieldNumber() != null) {
                        refereeFieldByLap
                                .computeIfAbsent(lap, k -> new HashMap<>())
                                .putIfAbsent(refId, match.getFieldNumber());
                    }
                }
            }
            playingByPhase.put(phaseId, playingByLap);
            fieldByPhase.put(phaseId, fieldByLap);
            opponentByPhase.put(phaseId, opponentByLap);
            refereeByPhase.put(phaseId, refereeByLap);
            refereeFieldByPhase.put(phaseId, refereeFieldByLap);
            maxLapByPhase.put(phaseId, maxLap);
        }

        // ── Step 3: Activity assignments per phase ────────────────────────────

        // phaseId → teamId → lapNumber → activityName (first activity wins)
        Map<UUID, Map<UUID, Map<Integer, String>>> activityByPhase = new HashMap<>();
        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            int maxLap = maxLapByPhase.getOrDefault(phaseId, 0);
            if (maxLap == 0 || activityTypes.isEmpty()) {
                activityByPhase.put(phaseId, Collections.emptyMap());
                continue;
            }
            Set<UUID> allTeamIds = new HashSet<>(teamById.keySet());
            ActivityAssignmentResult assignResult =
                    activityAssignmentService.assignActivities(
                            activityTypes,
                            playingByPhase.getOrDefault(phaseId, Collections.emptyMap()),
                            refereeByPhase.getOrDefault(phaseId, Collections.emptyMap()),
                            maxLap,
                            allTeamIds);

            Map<UUID, Map<Integer, String>> teamActivityMap = new HashMap<>();
            for (List<ActivityAssignment> assignments : assignResult.getAssignments().values()) {
                for (ActivityAssignment a : assignments) {
                    teamActivityMap
                            .computeIfAbsent(a.getTeamId(), k -> new HashMap<>())
                            .putIfAbsent(a.getLapNumber(), a.getActivityTypeName());
                }
            }
            activityByPhase.put(phaseId, teamActivityMap);
        }

        // ── Step 4: Build timeline ────────────────────────────────────────────

        LocalTime startTime = tournament.getPlannedStartTime();
        boolean hasTime = (startTime != null);

        List<PhaseConfig> phaseConfigs =
                buildPhaseConfigs(
                        phases, breaksByPhase, maxLapByPhase, lapTimeByPhase, lapBreakByPhase);

        List<TimelineEntry> timeline =
                hasTime
                        ? timelineCalculationService.calculate(
                                startTime, phaseConfigs, sectionBreakMinutes)
                        : Collections.emptyList();

        // ── Step 5: Initialize result rows ────────────────────────────────────

        Map<UUID, List<LaufzettelRow>> result = new HashMap<>();
        for (Team team : teams) {
            result.put(team.getId(), new ArrayList<>());
        }
        boolean multiPhase = phases.size() > 1;

        // ── Step 6: Assemble rows ─────────────────────────────────────────────

        if (!hasTime) {
            assembleWithoutTimeline(
                    teams,
                    phases,
                    playingByPhase,
                    fieldByPhase,
                    opponentByPhase,
                    refereeByPhase,
                    refereeFieldByPhase,
                    activityByPhase,
                    maxLapByPhase,
                    teamById,
                    result,
                    multiPhase);
        } else {
            assembleWithTimeline(
                    teams,
                    phases,
                    timeline,
                    playingByPhase,
                    fieldByPhase,
                    opponentByPhase,
                    refereeByPhase,
                    refereeFieldByPhase,
                    activityByPhase,
                    teamById,
                    result,
                    multiPhase);
        }

        return result;
    }

    /**
     * Returns whether the tournament has a planned start time (controls time column display).
     *
     * @param tournament the tournament to check
     * @return {@code true} if {@code plannedStartTime} is non-null
     */
    public boolean hasTime(Tournament tournament) {
        return tournament.getPlannedStartTime() != null;
    }

    // -------------------------------------------------------------------------
    // Private assembly helpers
    // -------------------------------------------------------------------------

    /** Assembles rows using the computed timeline for time windows. */
    private void assembleWithTimeline(
            List<Team> teams,
            List<Phase> phases,
            List<TimelineEntry> timeline,
            Map<UUID, Map<Integer, Set<UUID>>> playingByPhase,
            Map<UUID, Map<Integer, Map<UUID, Integer>>> fieldByPhase,
            Map<UUID, Map<Integer, Map<UUID, UUID>>> opponentByPhase,
            Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase,
            Map<UUID, Map<Integer, Map<UUID, Integer>>> refereeFieldByPhase,
            Map<UUID, Map<UUID, Map<Integer, String>>> activityByPhase,
            Map<UUID, Team> teamById,
            Map<UUID, List<LaufzettelRow>> result,
            boolean multiPhase) {

        // phaseNumber (1-based sequenceNumber) → Phase
        Map<Integer, Phase> phaseBySeqNumber = new HashMap<>();
        for (Phase phase : phases) {
            phaseBySeqNumber.put(phase.getSequenceNumber(), phase);
        }

        int lastPhaseNumber = -1;

        for (TimelineEntry entry : timeline) {
            int phaseNumber = entry.phaseNumber();
            Phase phase = phaseBySeqNumber.get(phaseNumber);
            if (phase == null) continue;
            UUID phaseId = phase.getId();

            // Phase header on first entry of each phase (multi-phase only) — AC11
            if (multiPhase && phaseNumber != lastPhaseNumber) {
                String phaseName =
                        phase.getDescription() != null && !phase.getDescription().isBlank()
                                ? phase.getDescription()
                                : "Phase " + phaseNumber;
                for (Team team : teams) {
                    List<LaufzettelRow> rows = result.get(team.getId());
                    if (rows != null) rows.add(LaufzettelRow.phaseHeader(phaseName));
                }
                lastPhaseNumber = phaseNumber;
            }

            TimelineEntryType type = entry.type();

            // LAP_BREAK: implicit gap — skip (per domain documentation in TimelineEntryType)
            if (type == TimelineEntryType.LAP_BREAK) continue;

            if (type == TimelineEntryType.INTRA_PHASE_BREAK
                    || type == TimelineEntryType.SECTION_BREAK) {
                // AC9: break separator rows
                String label =
                        (entry.label() != null && !entry.label().isBlank())
                                ? entry.label()
                                : "Pause";
                String tw = formatTimeWindow(entry.startTime(), entry.endTime());
                for (Team team : teams) {
                    List<LaufzettelRow> rows = result.get(team.getId());
                    if (rows != null) rows.add(LaufzettelRow.breakRow(label, tw));
                }
                continue;
            }

            // MATCH_ROUND with lapNumber > 0
            int lapNumber = entry.lapNumber();
            if (lapNumber <= 0) continue;

            String timeWindow = formatTimeWindow(entry.startTime(), entry.endTime());
            appendRoundRows(
                    teams,
                    phaseId,
                    lapNumber,
                    timeWindow,
                    playingByPhase,
                    fieldByPhase,
                    opponentByPhase,
                    refereeByPhase,
                    refereeFieldByPhase,
                    activityByPhase,
                    teamById,
                    result);
        }
    }

    /**
     * Assembles rows without a timeline (no planned start time — AC10). Derives lap range from
     * match data; all time windows are empty string.
     */
    private void assembleWithoutTimeline(
            List<Team> teams,
            List<Phase> phases,
            Map<UUID, Map<Integer, Set<UUID>>> playingByPhase,
            Map<UUID, Map<Integer, Map<UUID, Integer>>> fieldByPhase,
            Map<UUID, Map<Integer, Map<UUID, UUID>>> opponentByPhase,
            Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase,
            Map<UUID, Map<Integer, Map<UUID, Integer>>> refereeFieldByPhase,
            Map<UUID, Map<UUID, Map<Integer, String>>> activityByPhase,
            Map<UUID, Integer> maxLapByPhase,
            Map<UUID, Team> teamById,
            Map<UUID, List<LaufzettelRow>> result,
            boolean multiPhase) {

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();

            if (multiPhase) {
                String phaseName =
                        phase.getDescription() != null && !phase.getDescription().isBlank()
                                ? phase.getDescription()
                                : "Phase " + phase.getSequenceNumber();
                for (Team team : teams) {
                    List<LaufzettelRow> rows = result.get(team.getId());
                    if (rows != null) rows.add(LaufzettelRow.phaseHeader(phaseName));
                }
            }

            int maxLap = maxLapByPhase.getOrDefault(phaseId, 0);
            for (int lap = 1; lap <= maxLap; lap++) {
                appendRoundRows(
                        teams,
                        phaseId,
                        lap,
                        "",
                        playingByPhase,
                        fieldByPhase,
                        opponentByPhase,
                        refereeByPhase,
                        refereeFieldByPhase,
                        activityByPhase,
                        teamById,
                        result);
            }
        }
    }

    /**
     * Appends one round row per team for the given lap in the given phase. Determines round state
     * via PLAYING &gt; REFEREEING &gt; ACTIVITY &gt; FREE (AC6).
     */
    private void appendRoundRows(
            List<Team> teams,
            UUID phaseId,
            int lapNumber,
            String timeWindow,
            Map<UUID, Map<Integer, Set<UUID>>> playingByPhase,
            Map<UUID, Map<Integer, Map<UUID, Integer>>> fieldByPhase,
            Map<UUID, Map<Integer, Map<UUID, UUID>>> opponentByPhase,
            Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase,
            Map<UUID, Map<Integer, Map<UUID, Integer>>> refereeFieldByPhase,
            Map<UUID, Map<UUID, Map<Integer, String>>> activityByPhase,
            Map<UUID, Team> teamById,
            Map<UUID, List<LaufzettelRow>> result) {

        Set<UUID> playingTeams =
                playingByPhase
                        .getOrDefault(phaseId, Collections.emptyMap())
                        .getOrDefault(lapNumber, Collections.emptySet());
        Map<UUID, Integer> fieldByTeam =
                fieldByPhase
                        .getOrDefault(phaseId, Collections.emptyMap())
                        .getOrDefault(lapNumber, Collections.emptyMap());
        Map<UUID, UUID> opponentByTeam =
                opponentByPhase
                        .getOrDefault(phaseId, Collections.emptyMap())
                        .getOrDefault(lapNumber, Collections.emptyMap());
        Set<UUID> refereeTeams =
                refereeByPhase
                        .getOrDefault(phaseId, Collections.emptyMap())
                        .getOrDefault(lapNumber, Collections.emptySet());
        Map<UUID, Integer> refereeFieldByTeam =
                refereeFieldByPhase
                        .getOrDefault(phaseId, Collections.emptyMap())
                        .getOrDefault(lapNumber, Collections.emptyMap());
        Map<UUID, Map<Integer, String>> teamActivityMap =
                activityByPhase.getOrDefault(phaseId, Collections.emptyMap());

        for (Team team : teams) {
            UUID teamId = team.getId();
            List<LaufzettelRow> rows = result.get(teamId);
            if (rows == null) continue;

            if (playingTeams.contains(teamId)) {
                // PLAYING — AC4
                UUID opponentId = opponentByTeam.get(teamId);
                String opponentName = "";
                if (opponentId != null) {
                    Team opp = teamById.get(opponentId);
                    opponentName = opp != null ? teamDisplayName(opp) : "";
                }
                Integer field = fieldByTeam.get(teamId);
                String fieldStr = field != null ? String.valueOf(field) : "";
                rows.add(LaufzettelRow.playing(lapNumber, timeWindow, opponentName, fieldStr));

            } else if (refereeTeams.contains(teamId)) {
                // REFEREEING — AC5
                Integer field = refereeFieldByTeam.get(teamId);
                String fieldStr = field != null ? String.valueOf(field) : "";
                rows.add(LaufzettelRow.refereeing(lapNumber, timeWindow, fieldStr));

            } else {
                // ACTIVITY or FREE — AC7, AC8
                String activityName =
                        teamActivityMap.getOrDefault(teamId, Collections.emptyMap()).get(lapNumber);
                if (activityName != null) {
                    rows.add(LaufzettelRow.activity(lapNumber, timeWindow, activityName));
                } else {
                    rows.add(LaufzettelRow.free(lapNumber, timeWindow));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private utilities
    // -------------------------------------------------------------------------

    /** Returns a display name for a team (description if set, otherwise "Team {number}"). */
    private String teamDisplayName(Team team) {
        if (team.getDescription() != null && !team.getDescription().isBlank()) {
            return team.getDescription();
        }
        return "Team " + team.getTeamNumber();
    }

    /** Formats a time window as "HH:mm–HH:mm". Returns {@code ""} if either arg is null. */
    private String formatTimeWindow(LocalTime start, LocalTime end) {
        if (start == null || end == null) return "";
        return start.format(TIME_FMT) + "\u2013" + end.format(TIME_FMT);
    }

    /**
     * Builds {@link PhaseConfig} objects from phase data and their match-derived lap counts.
     *
     * <p>The lap count is the maximum {@code lapNumber} across all matches in the phase (i.e., the
     * number of rounds played). The {@link Phase#getCurrentLapNumber()} is NOT used here — it is a
     * progress counter that increments during the tournament, not a total count (which is known
     * only after slot-optimization).
     *
     * @param phases phases in sequenceNumber order
     * @param breaksByPhase phaseId → phase breaks
     * @param maxLapByPhase phaseId → max lap number (from match data)
     * @param lapTimeByPhase phaseId → lap duration override (falls back to {@value
     *     DEFAULT_LAP_TIME_MINUTES} min)
     * @param lapBreakByPhase phaseId → lap break duration override (falls back to {@value
     *     DEFAULT_LAP_BREAK_MINUTES} min)
     * @return list of PhaseConfig in phase order
     */
    private List<PhaseConfig> buildPhaseConfigs(
            List<Phase> phases,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            Map<UUID, Integer> maxLapByPhase,
            Map<UUID, Integer> lapTimeByPhase,
            Map<UUID, Integer> lapBreakByPhase) {

        List<PhaseConfig> configs = new ArrayList<>();
        int seqNumber = 1; // phaseNumber in timeline is 1-based sequenceNumber
        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            int lapCount = maxLapByPhase.getOrDefault(phaseId, 0);
            int lapTime = lapTimeByPhase.getOrDefault(phaseId, DEFAULT_LAP_TIME_MINUTES);
            int lapBreak = lapBreakByPhase.getOrDefault(phaseId, DEFAULT_LAP_BREAK_MINUTES);

            List<PhaseBreak> breaks = breaksByPhase.getOrDefault(phaseId, Collections.emptyList());
            List<PhaseBreakConfig> breakConfigs = new ArrayList<>();
            for (PhaseBreak pb : breaks) {
                breakConfigs.add(
                        new PhaseBreakConfig(
                                pb.getAfterLapNumber(), pb.getDurationMinutes(), pb.getLabel()));
            }

            configs.add(new PhaseConfig(seqNumber, lapCount, lapTime, lapBreak, breakConfigs));
            seqNumber++;
        }
        return configs;
    }
}
