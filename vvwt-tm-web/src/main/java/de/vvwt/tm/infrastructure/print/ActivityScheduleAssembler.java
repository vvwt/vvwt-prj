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
import org.springframework.stereotype.Service;

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

/**
 * Assembles the row list for the Mannschaftsfoto-Übersicht (activity schedule) print template.
 *
 * <p>This service is round-centric (photographer's view): for a given activity type, it produces
 * one row per round that has ≥ 1 assigned team — ordered by round number, empty rounds omitted
 * (AC3). Break separator rows are interleaved where the timeline has intra-phase or section
 * breaks between displayed rounds (AC4).
 *
 * <p>Unlike {@link LaufzettelAssembler} (team-centric), this assembler produces a single flat list
 * of rows (not a per-team map) for the specified activity type.
 *
 * <h2>No-start-time path (AC7)</h2>
 * <p>When {@code tournament.getPlannedStartTime()} is null, the timeline is empty. All time
 * windows are empty string, and no break separators are emitted (breaks are not determinable
 * without a timeline).
 *
 * <h2>Unassigned teams (AC6)</h2>
 * <p>Teams that could not be assigned (no free round) are returned separately from the row list
 * in the {@link ActivityScheduleModel} result.
 *
 * @see ActivityScheduleRow
 * @see ActivityScheduleModel
 * @see PrintController
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S09.story.md">Story E08S09</a>
 */
@Service
public class ActivityScheduleAssembler {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Default lap time in minutes — fallback when no E08S05 draft config is available. */
    static final int DEFAULT_LAP_TIME_MINUTES = 15;

    /** Default lap break in minutes — fallback. */
    static final int DEFAULT_LAP_BREAK_MINUTES = 5;

    private final TimelineCalculationService timelineCalculationService;
    private final ActivityAssignmentService activityAssignmentService;

    public ActivityScheduleAssembler(TimelineCalculationService timelineCalculationService,
                                     ActivityAssignmentService activityAssignmentService) {
        this.timelineCalculationService = timelineCalculationService;
        this.activityAssignmentService = activityAssignmentService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Assembles the activity schedule for a single activity type.
     *
     * @param tournament     the tournament (for start time and tenant context)
     * @param phases         all phases sorted by {@code sequenceNumber} ascending; must not be null
     * @param teams          all teams participating; must not be null
     * @param avatarsByPhase phaseId → TeamAvatars; must not be null
     * @param matchesByPhase phaseId → Matches; must not be null
     * @param breaksByPhase  phaseId → PhaseBreaks; must not be null
     * @param activityTypes  ALL activity types for this tournament (used to compute assignments
     *                       respecting capacity constraints across all types); must not be null
     * @param targetType     the specific activity type whose schedule to render; must not be null
     * @return assembled model with rows, summary, and unassigned teams; never null
     */
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

        Map<UUID, UUID> teamByAvatarId = new HashMap<>();
        for (List<TeamAvatar> avatarList : avatarsByPhase.values()) {
            for (TeamAvatar avatar : avatarList) {
                teamByAvatarId.put(avatar.getId(), avatar.getTeamId());
            }
        }

        // ── Step 2: Match-derived playing/referee sets per phase ──────────────

        // phaseId → lapNumber → set of playing teamIds
        Map<UUID, Map<Integer, Set<UUID>>> playingByPhase = new HashMap<>();
        // phaseId → lapNumber → set of refereeing teamIds
        Map<UUID, Map<Integer, Set<UUID>>> refereeByPhase = new HashMap<>();
        // phaseId → max lap number
        Map<UUID, Integer> maxLapByPhase = new HashMap<>();

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            List<Match> matches = matchesByPhase.getOrDefault(phaseId, Collections.emptyList());

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
                    refereeByLap.computeIfAbsent(lap, k -> new HashSet<>()).add(match.getRefereeTeamId());
                }
            }
            playingByPhase.put(phaseId, playingByLap);
            refereeByPhase.put(phaseId, refereeByLap);
            maxLapByPhase.put(phaseId, maxLap);
        }

        // ── Step 3: Activity assignments (all types, then filter to target) ───

        Set<UUID> allTeamIds = teams.stream().map(Team::getId).collect(Collectors.toSet());

        // Aggregate assignments for targetType across all phases.
        // Key: lapNumber → list of assigned team UUIDs (insertion order preserved)
        Map<Integer, List<UUID>> assignedByLap = new LinkedHashMap<>();
        Set<UUID> unassignedTeamIds = new HashSet<>();

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();
            int maxLap = maxLapByPhase.getOrDefault(phaseId, 0);
            if (maxLap == 0 || activityTypes.isEmpty()) continue;

            ActivityAssignmentResult result = activityAssignmentService.assignActivities(
                    activityTypes,
                    playingByPhase.getOrDefault(phaseId, Collections.emptyMap()),
                    refereeByPhase.getOrDefault(phaseId, Collections.emptyMap()),
                    maxLap,
                    allTeamIds);

            // Filter to targetType
            for (Map.Entry<ActivityType, List<ActivityAssignment>> entry :
                    result.getAssignments().entrySet()) {
                if (!targetType.getId().equals(entry.getKey().getId())) continue;
                for (ActivityAssignment a : entry.getValue()) {
                    assignedByLap.computeIfAbsent(a.getLapNumber(), k -> new ArrayList<>())
                            .add(a.getTeamId());
                }
            }

            // Collect unassigned for targetType
            for (Map.Entry<ActivityType, Set<UUID>> entry : result.getUnassignedTeams().entrySet()) {
                if (targetType.getId().equals(entry.getKey().getId())) {
                    unassignedTeamIds.addAll(entry.getValue());
                }
            }
        }

        // ── Step 4: Team display name lookup ─────────────────────────────────

        Map<UUID, String> teamDisplayNames = new HashMap<>();
        for (Team team : teams) {
            teamDisplayNames.put(team.getId(), teamDisplayName(team));
        }

        // ── Step 5: Timeline (for time windows and break context) ─────────────

        LocalTime startTime = tournament.getPlannedStartTime();
        boolean hasTime = (startTime != null);

        List<TimelineEntry> timeline = Collections.emptyList();
        if (hasTime) {
            List<PhaseConfig> phaseConfigs = buildPhaseConfigs(phases, breaksByPhase, maxLapByPhase);
            timeline = timelineCalculationService.calculate(startTime, phaseConfigs, 0);
        }

        // Build lap → timeline entry lookup (MATCH_ROUND only)
        Map<Integer, TimelineEntry> timelineByLap = new HashMap<>();
        for (TimelineEntry entry : timeline) {
            if (entry.type() == TimelineEntryType.MATCH_ROUND && entry.lapNumber() > 0) {
                timelineByLap.put(entry.lapNumber(), entry);
            }
        }

        // ── Step 6: Assemble rows ─────────────────────────────────────────────

        List<ActivityScheduleRow> rows = new ArrayList<>();

        if (!hasTime) {
            // No timeline — simple iteration over assigned laps in ascending order
            List<Integer> sortedLaps = new ArrayList<>(assignedByLap.keySet());
            Collections.sort(sortedLaps);
            for (int lap : sortedLaps) {
                List<UUID> teamIds = assignedByLap.get(lap);
                if (teamIds == null || teamIds.isEmpty()) continue;
                String names = buildTeamNames(teamIds, teamDisplayNames);
                rows.add(ActivityScheduleRow.dataRow(lap, "", names));
            }
        } else {
            // Use timeline to order rows and interleave break separators
            // Track which laps have been emitted (for break interleaving)
            Set<Integer> emittedLaps = new HashSet<>();

            // Walk the timeline in order; emit data rows for MATCH_ROUND entries that have
            // assignments, and break rows for INTRA_PHASE_BREAK / SECTION_BREAK that fall
            // between two emitted (or to-be-emitted) data rows.
            //
            // Strategy: two-pass
            //   Pass 1: collect all assigned lap numbers (sorted) for quick lookup
            //   Pass 2: walk timeline, emit breaks only if the next data lap has assignments

            Set<Integer> assignedLaps = new HashSet<>(assignedByLap.keySet());

            // Determine which laps follow a break that also has an assigned lap before it
            // (simplified: emit a break separator if the PREVIOUS emitted row was a data row
            //  and the current timeline entry is a break)

            boolean lastWasData = false;

            for (TimelineEntry entry : timeline) {
                TimelineEntryType type = entry.type();

                if (type == TimelineEntryType.LAP_BREAK) {
                    // Always skip lap breaks (same as Laufzettel)
                    continue;
                }

                if (type == TimelineEntryType.INTRA_PHASE_BREAK || type == TimelineEntryType.SECTION_BREAK) {
                    // Emit break separator only if previous row was a data row and there is a
                    // data row ahead (i.e., more assigned laps remain after this break)
                    boolean moreDataAhead = hasAssignedLapAfter(timeline, entry, assignedLaps);
                    if (lastWasData && moreDataAhead) {
                        String label = (entry.label() != null && !entry.label().isBlank())
                                ? entry.label() : "Pause";
                        String tw = formatTimeWindow(entry.startTime(), entry.endTime());
                        rows.add(ActivityScheduleRow.breakRow(label, tw));
                    }
                    lastWasData = false;
                    continue;
                }

                if (type == TimelineEntryType.MATCH_ROUND) {
                    int lap = entry.lapNumber();
                    if (lap <= 0) continue;  // zero-lap phase marker
                    if (!assignedLaps.contains(lap)) {
                        // No assignment in this round — skip (AC3: empty rounds omitted)
                        continue;
                    }
                    List<UUID> teamIds = assignedByLap.get(lap);
                    if (teamIds == null || teamIds.isEmpty()) continue;

                    String tw = formatTimeWindow(entry.startTime(), entry.endTime());
                    String names = buildTeamNames(teamIds, teamDisplayNames);
                    rows.add(ActivityScheduleRow.dataRow(lap, tw, names));
                    emittedLaps.add(lap);
                    lastWasData = true;
                }
            }
        }

        // ── Step 7: Summary ───────────────────────────────────────────────────

        int totalAssignedTeams = assignedByLap.values().stream().mapToInt(List::size).sum();
        int roundCount = (int) rows.stream().filter(ActivityScheduleRow::isDataRow).count();

        // ── Step 8: Unassigned team names ─────────────────────────────────────

        List<String> unassignedNames = new ArrayList<>();
        for (UUID uid : unassignedTeamIds) {
            String name = teamDisplayNames.get(uid);
            if (name != null) unassignedNames.add(name);
        }
        Collections.sort(unassignedNames);

        return new ActivityScheduleModel(rows, totalAssignedTeams, roundCount, unassignedNames, hasTime);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if there is a MATCH_ROUND entry in the timeline after {@code breakEntry}
     * whose lapNumber is in {@code assignedLaps}.
     */
    private boolean hasAssignedLapAfter(List<TimelineEntry> timeline,
                                         TimelineEntry breakEntry,
                                         Set<Integer> assignedLaps) {
        boolean pastBreak = false;
        for (TimelineEntry e : timeline) {
            if (e == breakEntry) {
                pastBreak = true;
                continue;
            }
            if (pastBreak && e.type() == TimelineEntryType.MATCH_ROUND
                    && e.lapNumber() > 0 && assignedLaps.contains(e.lapNumber())) {
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
        return start.format(TIME_FMT) + "\u2013" + end.format(TIME_FMT);
    }

    /**
     * Builds {@link PhaseConfig} objects from phase data and match-derived lap counts.
     * Uses default lap time / break time when no explicit config is available (E08S05 not yet
     * applied for this tournament).
     */
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
                breakConfigs.add(new PhaseBreakConfig(
                        pb.getAfterLapNumber(), pb.getDurationMinutes(), pb.getLabel()));
            }

            configs.add(new PhaseConfig(
                    seqNumber, lapCount,
                    DEFAULT_LAP_TIME_MINUTES, DEFAULT_LAP_BREAK_MINUTES,
                    breakConfigs));
            seqNumber++;
        }
        return configs;
    }
}
