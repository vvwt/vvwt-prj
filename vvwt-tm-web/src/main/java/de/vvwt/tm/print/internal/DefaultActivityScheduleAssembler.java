// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.print.internal;

import de.vvwt.tm.print.ActivityScheduleAssembler;
import de.vvwt.tm.print.ActivityScheduleModel;
import de.vvwt.tm.print.ActivityScheduleRow;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link ActivityScheduleAssembler}.
 *
 * <p>Assembles the row list for the Mannschaftsfoto-Übersicht (activity schedule) print template.
 * This service is round-centric (photographer's view): for a given activity type, it produces one
 * row per round that has ≥ 1 assigned team — ordered by round number, empty rounds omitted. Break
 * separator rows are interleaved where the timeline has intra-phase or section breaks between
 * displayed rounds.
 *
 * <p>Fresh RED-first TDD reconstruction per DEC-22 Iron Law. No copy-paste from the legacy {@code
 * ActivityScheduleAssembler} (deleted at E24S07 atomic cutover; DEC-41 §3 strict).
 *
 * @see ActivityScheduleAssembler
 * @see ActivityScheduleModel
 * @see ActivityScheduleRow
 * @since E24S03
 */
@Service("printActivityScheduleAssembler")
public class DefaultActivityScheduleAssembler implements ActivityScheduleAssembler {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Default lap time in minutes — fallback when no explicit phase config is available. */
    static final int DEFAULT_LAP_TIME_MINUTES = 15;

    /** Default lap break in minutes — fallback. */
    static final int DEFAULT_LAP_BREAK_MINUTES = 5;

    private final TimelineCalculationService timelineCalculationService;
    private final ActivityAssignmentService activityAssignmentService;

    public DefaultActivityScheduleAssembler(
            TimelineCalculationService timelineCalculationService,
            ActivityAssignmentService activityAssignmentService) {
        this.timelineCalculationService = timelineCalculationService;
        this.activityAssignmentService = activityAssignmentService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Override
    public ActivityScheduleModel assemble(
            Tournament tournament,
            List<Phase> phases,
            List<Team> teams,
            Map<UUID, List<TeamAvatar>> avatarsByPhase,
            Map<UUID, List<Match>> matchesByPhase,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            List<ActivityType> activityTypes,
            ActivityType targetType) {

        if (phases == null || phases.isEmpty() || teams == null || teams.isEmpty()) {
            return ActivityScheduleModel.empty();
        }

        // ── Step 1: Avatar → team lookup ─────────────────────────────────────
        Map<UUID, UUID> teamByAvatarId = buildTeamByAvatarId(avatarsByPhase);

        // ── Step 2: Match-derived playing/referee sets per phase ──────────────
        Map<UUID, Map<Integer, Set<UUID>>> playingByPhase = new HashMap<>();
        Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase = new HashMap<>();
        Map<UUID, Integer> maxLapByPhase = new HashMap<>();

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            List<Match> matches = matchesByPhase.getOrDefault(phaseId, Collections.emptyList());
            collectMatchData(
                    matches,
                    teamByAvatarId,
                    phaseId,
                    playingByPhase,
                    refereeByPhase,
                    maxLapByPhase);
        }

        // ── Step 3: Activity assignments (all types, filter to target) ────────
        Set<UUID> allTeamIds = teams.stream().map(Team::getId).collect(Collectors.toSet());
        Map<Integer, List<UUID>> assignedByLap = new LinkedHashMap<>();
        Set<UUID> unassignedTeamIds = new HashSet<>();

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            int maxLap = maxLapByPhase.getOrDefault(phaseId, 0);
            if (maxLap == 0 || activityTypes.isEmpty()) continue;

            ActivityAssignmentResult result =
                    activityAssignmentService.assignActivities(
                            activityTypes,
                            playingByPhase.getOrDefault(phaseId, Collections.emptyMap()),
                            refereeByPhase.getOrDefault(phaseId, Collections.emptyMap()),
                            maxLap,
                            allTeamIds);

            collectAssignmentsForTarget(result, targetType, assignedByLap, unassignedTeamIds);
        }

        // ── Step 4: Team display name lookup ─────────────────────────────────
        Map<UUID, String> teamDisplayNames = buildTeamDisplayNames(teams);

        // ── Step 5: Timeline (for time windows and break context) ─────────────
        LocalTime startTime = tournament.getPlannedStartTime();
        boolean hasTime = (startTime != null);

        List<TimelineEntry> timeline = Collections.emptyList();
        if (hasTime) {
            List<PhaseConfig> phaseConfigs =
                    buildPhaseConfigs(phases, breaksByPhase, maxLapByPhase);
            timeline = timelineCalculationService.calculate(startTime, phaseConfigs, 0);
        }

        // ── Step 6: Assemble rows ─────────────────────────────────────────────
        List<ActivityScheduleRow> rows =
                buildRows(hasTime, timeline, assignedByLap, teamDisplayNames);

        // ── Step 7: Summary ───────────────────────────────────────────────────
        int totalAssignedTeams = assignedByLap.values().stream().mapToInt(List::size).sum();
        int roundCount = (int) rows.stream().filter(ActivityScheduleRow::isDataRow).count();

        // ── Step 8: Unassigned team names ─────────────────────────────────────
        List<String> unassignedNames = buildUnassignedNames(unassignedTeamIds, teamDisplayNames);

        return new ActivityScheduleModel(
                rows, totalAssignedTeams, roundCount, unassignedNames, hasTime);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<UUID, UUID> buildTeamByAvatarId(Map<UUID, List<TeamAvatar>> avatarsByPhase) {
        Map<UUID, UUID> teamByAvatarId = new HashMap<>();
        for (List<TeamAvatar> avatarList : avatarsByPhase.values()) {
            for (TeamAvatar avatar : avatarList) {
                teamByAvatarId.put(avatar.getId(), avatar.getTeamId());
            }
        }
        return teamByAvatarId;
    }

    private void collectMatchData(
            List<Match> matches,
            Map<UUID, UUID> teamByAvatarId,
            UUID phaseId,
            Map<UUID, Map<Integer, Set<UUID>>> playingByPhase,
            Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase,
            Map<UUID, Integer> maxLapByPhase) {

        Map<Integer, Set<UUID>> playingByLap = new HashMap<>();
        Map<Integer, Set<UUID>> refereeByLap = new HashMap<>();
        int maxLap = 0;

        for (Match match : matches) {
            if (match.getLapNumber() == null) continue;
            int lap = match.getLapNumber();
            if (lap > maxLap) maxLap = lap;

            UUID t1 = teamByAvatarId.get(match.getMemberAvatar1Id());
            UUID t2 = teamByAvatarId.get(match.getMemberAvatar2Id());
            if (t1 != null) playingByLap.computeIfAbsent(lap, k -> new HashSet<>()).add(t1);
            if (t2 != null) playingByLap.computeIfAbsent(lap, k -> new HashSet<>()).add(t2);

            if (match.getRefereeTeamId() != null) {
                refereeByLap
                        .computeIfAbsent(lap, k -> new HashSet<>())
                        .add(match.getRefereeTeamId());
            }
        }

        playingByPhase.put(phaseId, playingByLap);
        refereeByPhase.put(phaseId, refereeByLap);
        maxLapByPhase.put(phaseId, maxLap);
    }

    private void collectAssignmentsForTarget(
            ActivityAssignmentResult result,
            ActivityType targetType,
            Map<Integer, List<UUID>> assignedByLap,
            Set<UUID> unassignedTeamIds) {

        for (Map.Entry<ActivityType, List<ActivityAssignment>> entry :
                result.getAssignments().entrySet()) {
            if (!targetType.getId().equals(entry.getKey().getId())) continue;
            for (ActivityAssignment a : entry.getValue()) {
                assignedByLap
                        .computeIfAbsent(a.getLapNumber(), k -> new ArrayList<>())
                        .add(a.getTeamId());
            }
        }

        for (Map.Entry<ActivityType, Set<UUID>> entry : result.getUnassignedTeams().entrySet()) {
            if (targetType.getId().equals(entry.getKey().getId())) {
                unassignedTeamIds.addAll(entry.getValue());
            }
        }
    }

    private Map<UUID, String> buildTeamDisplayNames(List<Team> teams) {
        Map<UUID, String> teamDisplayNames = new HashMap<>();
        for (Team team : teams) {
            teamDisplayNames.put(team.getId(), teamDisplayName(team));
        }
        return teamDisplayNames;
    }

    private List<ActivityScheduleRow> buildRows(
            boolean hasTime,
            List<TimelineEntry> timeline,
            Map<Integer, List<UUID>> assignedByLap,
            Map<UUID, String> teamDisplayNames) {

        List<ActivityScheduleRow> rows = new ArrayList<>();

        if (!hasTime) {
            // No timeline — simple iteration over assigned laps in ascending order
            List<Integer> sortedLaps = new ArrayList<>(assignedByLap.keySet());
            Collections.sort(sortedLaps);
            for (int lap : sortedLaps) {
                List<UUID> teamIds = assignedByLap.get(lap);
                if (teamIds == null || teamIds.isEmpty()) continue;
                rows.add(
                        ActivityScheduleRow.dataRow(
                                lap, "", buildTeamNames(teamIds, teamDisplayNames)));
            }
        } else {
            // Use timeline to order rows and interleave break separators
            Set<Integer> assignedLaps = new HashSet<>(assignedByLap.keySet());
            boolean lastWasData = false;

            for (TimelineEntry entry : timeline) {
                TimelineEntryType type = entry.type();

                if (type == TimelineEntryType.LAP_BREAK) {
                    continue; // lap breaks skipped
                }

                if (type == TimelineEntryType.INTRA_PHASE_BREAK
                        || type == TimelineEntryType.SECTION_BREAK) {
                    boolean moreDataAhead = hasAssignedLapAfter(timeline, entry, assignedLaps);
                    if (lastWasData && moreDataAhead) {
                        String label =
                                (entry.label() != null && !entry.label().isBlank())
                                        ? entry.label()
                                        : "Pause";
                        String tw = formatTimeWindow(entry.startTime(), entry.endTime());
                        rows.add(ActivityScheduleRow.breakRow(label, tw));
                    }
                    lastWasData = false;
                    continue;
                }

                if (type == TimelineEntryType.MATCH_ROUND) {
                    int lap = entry.lapNumber();
                    if (lap <= 0) continue;
                    if (!assignedLaps.contains(lap)) continue; // AC3: empty rounds omitted

                    List<UUID> teamIds = assignedByLap.get(lap);
                    if (teamIds == null || teamIds.isEmpty()) continue;

                    String tw = formatTimeWindow(entry.startTime(), entry.endTime());
                    rows.add(
                            ActivityScheduleRow.dataRow(
                                    lap, tw, buildTeamNames(teamIds, teamDisplayNames)));
                    lastWasData = true;
                }
            }
        }

        return rows;
    }

    private List<String> buildUnassignedNames(
            Set<UUID> unassignedTeamIds, Map<UUID, String> teamDisplayNames) {
        List<String> unassignedNames = new ArrayList<>();
        for (UUID uid : unassignedTeamIds) {
            String name = teamDisplayNames.get(uid);
            if (name != null) unassignedNames.add(name);
        }
        Collections.sort(unassignedNames);
        return unassignedNames;
    }

    /**
     * Returns true if there is a MATCH_ROUND entry in the timeline after {@code breakEntry} whose
     * lapNumber is in {@code assignedLaps}.
     */
    private boolean hasAssignedLapAfter(
            List<TimelineEntry> timeline, TimelineEntry breakEntry, Set<Integer> assignedLaps) {
        boolean pastBreak = false;
        for (TimelineEntry e : timeline) {
            if (e == breakEntry) {
                pastBreak = true;
                continue;
            }
            if (pastBreak
                    && e.type() == TimelineEntryType.MATCH_ROUND
                    && e.lapNumber() > 0
                    && assignedLaps.contains(e.lapNumber())) {
                return true;
            }
        }
        return false;
    }

    /** Builds a comma-separated display string of team names for the given team UUIDs. */
    private String buildTeamNames(List<UUID> teamIds, Map<UUID, String> displayNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < teamIds.size(); i++) {
            if (i > 0) sb.append(", ");
            String name = displayNames.get(teamIds.get(i));
            sb.append(name != null ? name : "?");
        }
        return sb.toString();
    }

    /** Returns a display name for a team (description if set, else "Team {number}"). */
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

    /** Builds {@link PhaseConfig} objects from phase data and match-derived lap counts. */
    private List<PhaseConfig> buildPhaseConfigs(
            List<Phase> phases,
            Map<UUID, List<PhaseBreak>> breaksByPhase,
            Map<UUID, Integer> maxLapByPhase) {

        List<PhaseConfig> configs = new ArrayList<>();
        int seqNumber = 1;
        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            int lapCount = maxLapByPhase.getOrDefault(phaseId, 0);

            List<PhaseBreak> breaks = breaksByPhase.getOrDefault(phaseId, Collections.emptyList());
            List<PhaseBreakConfig> breakConfigs = new ArrayList<>();
            for (PhaseBreak pb : breaks) {
                breakConfigs.add(
                        new PhaseBreakConfig(
                                pb.getAfterLapNumber(), pb.getDurationMinutes(), pb.getLabel()));
            }

            configs.add(
                    new PhaseConfig(
                            seqNumber,
                            lapCount,
                            DEFAULT_LAP_TIME_MINUTES,
                            DEFAULT_LAP_BREAK_MINUTES,
                            breakConfigs));
            seqNumber++;
        }
        return configs;
    }
}
