package de.vvwt.tm.print.internal;

import de.vvwt.tm.print.LaufzettelAssembler;
import de.vvwt.tm.print.LaufzettelRow;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakConfig;
import de.vvwt.tm.tournament.PhaseConfig;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.TimelineEntry;
import de.vvwt.tm.tournament.TimelineEntryType;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.activity.ActivityAssignment;
import de.vvwt.tm.tournament.activity.ActivityAssignmentResult;
import de.vvwt.tm.tournament.activity.ActivityAssignmentService;
import de.vvwt.tm.tournament.activity.ActivityType;
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
 * Default implementation of {@link LaufzettelAssembler} — fresh reconstruction per E24S02.
 *
 * <p>Authored TDD RED-first per DEC-22 Iron Law. Every line of production code was preceded by a
 * failing test committed at {@code ef3ca16} (DefaultLaufzettelAssemblerTest RED). No copy-paste
 * from the legacy {@code LaufzettelAssembler} (deleted at E24S07 atomic cutover); legacy body
 * consulted as BLACK-BOX reference for algorithmic patterns per DEC-41 §3 strict.
 *
 * <p>Placed at {@code de.vvwt.tm.print.internal} per DEC-35 naming canon ({@code Default*}
 * implementation in {@code .internal} package). {@code @Service} annotation per DEC-35 impl
 * convention. Constructor injection follows Spring idioms.
 *
 * <h2>Algorithm overview</h2>
 *
 * <ol>
 *   <li>Build avatar→team lookup and per-phase playing/refereeing/opponent/field maps from matches.
 *   <li>Resolve activity assignments per phase via {@link ActivityAssignmentService}.
 *   <li>Derive timeline (when start time present) or use no-timeline path.
 *   <li>Assemble one {@link LaufzettelRow} per team per timeline entry (round/break) or per lap in
 *       no-timeline path. Phase header rows inserted at phase boundaries in multi-phase schedules.
 * </ol>
 *
 * <h2>Round state priority (AC6)</h2>
 *
 * <p>PLAYING &gt; REFEREEING &gt; ACTIVITY &gt; FREE.
 *
 * <h2>Legacy coexistence</h2>
 *
 * <p>Spring bean name = {@code defaultLaufzettelAssembler} (derived from class name). Legacy bean
 * name = {@code laufzettelAssembler}. Different names → no collision.
 *
 * @see LaufzettelAssembler
 * @see de.vvwt.tm.print.LaufzettelRow
 */
@Service
public class DefaultLaufzettelAssembler implements LaufzettelAssembler {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Default lap time in minutes used when no explicit phase configuration is provided. */
    static final int DEFAULT_LAP_TIME_MINUTES = 15;

    /** Default lap break time in minutes used when no explicit phase configuration is provided. */
    static final int DEFAULT_LAP_BREAK_MINUTES = 5;

    private final TimelineCalculationService timelineCalculationService;
    private final ActivityAssignmentService activityAssignmentService;

    public DefaultLaufzettelAssembler(
            TimelineCalculationService timelineCalculationService,
            ActivityAssignmentService activityAssignmentService) {
        this.timelineCalculationService = timelineCalculationService;
        this.activityAssignmentService = activityAssignmentService;
    }

    // -------------------------------------------------------------------------
    // Public API — implements LaufzettelAssembler interface
    // -------------------------------------------------------------------------

    @Override
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

    @Override
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

        // ── Step 1: Build team and avatar lookup structures ───────────────────

        Map<UUID, Team> teamById = new HashMap<>();
        for (Team team : teams) {
            teamById.put(team.getId(), team);
        }

        // avatar UUID → team UUID
        Map<UUID, UUID> teamByAvatarId = new HashMap<>();
        for (List<TeamAvatar> avatarList : avatarsByPhase.values()) {
            for (TeamAvatar avatar : avatarList) {
                teamByAvatarId.put(avatar.getId(), avatar.getTeamId());
            }
        }

        // ── Step 2: Per-phase playing/referee/field/opponent lookups ──────────

        // phaseId → lapNumber → set of playing teamIds
        Map<UUID, Map<Integer, Set<UUID>>> playingByPhase = new HashMap<>();
        // phaseId → lapNumber → teamId → fieldNumber
        Map<UUID, Map<Integer, Map<UUID, Integer>>> fieldByPhase = new HashMap<>();
        // phaseId → lapNumber → teamId → opponentTeamId
        Map<UUID, Map<Integer, Map<UUID, UUID>>> opponentByPhase = new HashMap<>();
        // phaseId → lapNumber → set of refereeing teamIds
        Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase = new HashMap<>();
        // phaseId → lapNumber → refereeTeamId → fieldNumber
        Map<UUID, Map<Integer, Map<UUID, Integer>>> refereeFieldByPhase = new HashMap<>();
        // phaseId → maxLapNumber derived from match data
        Map<UUID, Integer> maxLapByPhase = new HashMap<>();
        // phaseId → lapNumber → refereeTeamId → {teamAName, teamBName} (E53S02 AC5)
        Map<UUID, Map<Integer, Map<UUID, String[]>>> refereeMatchTeamsByPhase = new HashMap<>();

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            List<Match> matches =
                    matchesByPhase != null
                            ? matchesByPhase.getOrDefault(phaseId, Collections.emptyList())
                            : Collections.emptyList();

            Map<Integer, Set<UUID>> playingByLap = new HashMap<>();
            Map<Integer, Map<UUID, Integer>> fieldByLap = new HashMap<>();
            Map<Integer, Map<UUID, UUID>> opponentByLap = new HashMap<>();
            Map<Integer, Set<UUID>> refereeByLap = new HashMap<>();
            Map<Integer, Map<UUID, Integer>> refereeFieldByLap = new HashMap<>();
            Map<Integer, Map<UUID, String[]>> refereeMatchTeamsByLap = new HashMap<>();
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
                    // Resolve match team names for refereeing row context (E53S02 AC5)
                    Team matchTeam1 = t1 != null ? teamById.get(t1) : null;
                    Team matchTeam2 = t2 != null ? teamById.get(t2) : null;
                    String teamAName = matchTeam1 != null ? teamDisplayName(matchTeam1) : "";
                    String teamBName = matchTeam2 != null ? teamDisplayName(matchTeam2) : "";
                    refereeMatchTeamsByLap
                            .computeIfAbsent(lap, k -> new HashMap<>())
                            .putIfAbsent(refId, new String[] {teamAName, teamBName});
                }
            }

            playingByPhase.put(phaseId, playingByLap);
            fieldByPhase.put(phaseId, fieldByLap);
            opponentByPhase.put(phaseId, opponentByLap);
            refereeByPhase.put(phaseId, refereeByLap);
            refereeFieldByPhase.put(phaseId, refereeFieldByLap);
            refereeMatchTeamsByPhase.put(phaseId, refereeMatchTeamsByLap);
            maxLapByPhase.put(phaseId, maxLap);
        }

        // ── Step 3: Activity assignments per phase ────────────────────────────

        // phaseId → teamId → lapNumber → activityName
        Map<UUID, Map<UUID, Map<Integer, String>>> activityByPhase = new HashMap<>();
        List<ActivityType> safeActivityTypes =
                activityTypes != null ? activityTypes : Collections.emptyList();

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            int maxLap = maxLapByPhase.getOrDefault(phaseId, 0);
            if (maxLap == 0 || safeActivityTypes.isEmpty()) {
                activityByPhase.put(phaseId, Collections.emptyMap());
                continue;
            }
            Set<UUID> allTeamIds = new HashSet<>(teamById.keySet());
            ActivityAssignmentResult assignResult =
                    activityAssignmentService.assignActivities(
                            safeActivityTypes,
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
                    refereeMatchTeamsByPhase,
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
                    refereeMatchTeamsByPhase,
                    activityByPhase,
                    teamById,
                    result,
                    multiPhase);
        }

        return result;
    }

    @Override
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
            Map<UUID, Map<Integer, Map<UUID, String[]>>> refereeMatchTeamsByPhase,
            Map<UUID, Map<UUID, Map<Integer, String>>> activityByPhase,
            Map<UUID, Team> teamById,
            Map<UUID, List<LaufzettelRow>> result,
            boolean multiPhase) {

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
                String phaseName = resolvePhaseHeaderName(phase);
                for (Team team : teams) {
                    List<LaufzettelRow> rows = result.get(team.getId());
                    if (rows != null) rows.add(LaufzettelRow.phaseHeader(phaseName));
                }
                lastPhaseNumber = phaseNumber;
            }

            TimelineEntryType type = entry.type();

            // LAP_BREAK: implicit gap — skipped (per domain documentation in TimelineEntryType)
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
                    refereeMatchTeamsByPhase,
                    activityByPhase,
                    teamById,
                    result);
        }
    }

    /** Assembles rows without a timeline (no planned start time — AC10). */
    private void assembleWithoutTimeline(
            List<Team> teams,
            List<Phase> phases,
            Map<UUID, Map<Integer, Set<UUID>>> playingByPhase,
            Map<UUID, Map<Integer, Map<UUID, Integer>>> fieldByPhase,
            Map<UUID, Map<Integer, Map<UUID, UUID>>> opponentByPhase,
            Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase,
            Map<UUID, Map<Integer, Map<UUID, Integer>>> refereeFieldByPhase,
            Map<UUID, Map<Integer, Map<UUID, String[]>>> refereeMatchTeamsByPhase,
            Map<UUID, Map<UUID, Map<Integer, String>>> activityByPhase,
            Map<UUID, Integer> maxLapByPhase,
            Map<UUID, Team> teamById,
            Map<UUID, List<LaufzettelRow>> result,
            boolean multiPhase) {

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();

            if (multiPhase) {
                String phaseName = resolvePhaseHeaderName(phase);
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
                        refereeMatchTeamsByPhase,
                        activityByPhase,
                        teamById,
                        result);
            }
        }
    }

    /**
     * Appends one round row per team for the given lap in the given phase. Priority: PLAYING &gt;
     * REFEREEING &gt; ACTIVITY &gt; FREE (AC6).
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
            Map<UUID, Map<Integer, Map<UUID, String[]>>> refereeMatchTeamsByPhase,
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
        Map<UUID, String[]> refereeMatchTeamsByTeam =
                refereeMatchTeamsByPhase
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
                // E53S07: Match.fieldNumber is 0-based (slot-opt assigns 0..N-1); display is
                // 1-based (venue signage starts at 1). Apply +1 at the read side only — storage
                // and all other consumers (scoring device-field comparison, slot-opt position
                // arithmetic) are unaffected. Null guard preserved for unassigned matches.
                String fieldStr = field != null ? String.valueOf(field + 1) : "";
                rows.add(LaufzettelRow.playing(lapNumber, timeWindow, opponentName, fieldStr));

            } else if (refereeTeams.contains(teamId)) {
                // REFEREEING — AC5 (E53S02: include match team pair context)
                Integer field = refereeFieldByTeam.get(teamId);
                // E53S07: same +1 conversion for REFEREEING row (see PLAYING comment above).
                String fieldStr = field != null ? String.valueOf(field + 1) : "";
                String[] matchTeams = refereeMatchTeamsByTeam.get(teamId);
                String teamAName =
                        (matchTeams != null && matchTeams.length > 0) ? matchTeams[0] : "";
                String teamBName =
                        (matchTeams != null && matchTeams.length > 1) ? matchTeams[1] : "";
                rows.add(
                        LaufzettelRow.refereeing(
                                lapNumber, timeWindow, fieldStr, teamAName, teamBName));

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

    /** Resolves the display name for a phase header row. */
    private String resolvePhaseHeaderName(Phase phase) {
        if (phase.getDescription() != null && !phase.getDescription().isBlank()) {
            return phase.getDescription();
        }
        return "Phase " + phase.getSequenceNumber();
    }

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
        return start.format(TIME_FMT) + "–" + end.format(TIME_FMT);
    }

    /**
     * Builds {@link PhaseConfig} objects from phase data and their match-derived lap counts.
     *
     * <p>The lap count is the maximum {@code lapNumber} across all matches in the phase.
     */
    private List<PhaseConfig> buildPhaseConfigs(
            List<Phase> phases,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            Map<UUID, Integer> maxLapByPhase,
            Map<UUID, Integer> lapTimeByPhase,
            Map<UUID, Integer> lapBreakByPhase) {

        List<PhaseConfig> configs = new ArrayList<>();
        int seqNumber = 1;
        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            int lapCount = maxLapByPhase.getOrDefault(phaseId, 0);
            int lapTime =
                    lapTimeByPhase != null
                            ? lapTimeByPhase.getOrDefault(phaseId, DEFAULT_LAP_TIME_MINUTES)
                            : DEFAULT_LAP_TIME_MINUTES;
            int lapBreak =
                    lapBreakByPhase != null
                            ? lapBreakByPhase.getOrDefault(phaseId, DEFAULT_LAP_BREAK_MINUTES)
                            : DEFAULT_LAP_BREAK_MINUTES;

            List<PhaseBreak> breaks =
                    (breaksByPhase != null)
                            ? breaksByPhase.getOrDefault(phaseId, Collections.emptyList())
                            : Collections.emptyList();
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
