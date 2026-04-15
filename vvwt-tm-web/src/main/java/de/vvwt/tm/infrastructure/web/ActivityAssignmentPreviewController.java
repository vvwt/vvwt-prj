package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.ActivityTypeService;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.activity.ActivityAssignment;
import de.vvwt.tm.domain.activity.ActivityAssignmentResult;
import de.vvwt.tm.domain.activity.ActivityAssignmentService;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.dto.ActivityAssignmentPreviewResponse;
import de.vvwt.tm.infrastructure.web.dto.ActivityAssignmentPreviewResponse.ActivityTypeAssignment;
import de.vvwt.tm.infrastructure.web.dto.ActivityAssignmentPreviewResponse.LapEntry;
import de.vvwt.tm.infrastructure.web.dto.ActivityAssignmentPreviewResponse.TeamRef;
import de.vvwt.tm.infrastructure.web.dto.ActivityAssignmentPreviewResponse.UnassignedEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for the activity assignment preview (E08S06, AC2, AC9).
 *
 * <h2>Endpoint</h2>
 * <ul>
 *   <li>GET /api/tournaments/{tournamentId}/activity-assignments — returns computed assignments</li>
 * </ul>
 *
 * <h2>Phase resolution (AC2, AC6)</h2>
 * <p>When no {@code phaseId} query parameter is provided, the controller resolves the phase
 * automatically: ACTIVE phase preferred, else the last PENDING phase (by sequenceNumber).
 * If no suitable phase exists or no matches are slotted, returns an empty preview (not 404).
 *
 * <h2>Security (AC9)</h2>
 * <p>All /api/** endpoints require HTTP Basic authentication. Tenant scoping is enforced
 * at the repository layer (DEC-5, DEC-17).
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S06.story.md">Story E08S06</a>
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/activity-assignments")
public class ActivityAssignmentPreviewController {

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamRepository teamRepository;
    private final ActivityTypeService activityTypeService;
    private final ActivityAssignmentService activityAssignmentService;

    public ActivityAssignmentPreviewController(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamRepository teamRepository,
            ActivityTypeService activityTypeService,
            ActivityAssignmentService activityAssignmentService) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamRepository = teamRepository;
        this.activityTypeService = activityTypeService;
        this.activityAssignmentService = activityAssignmentService;
    }

    // -------------------------------------------------------------------------
    // AC2 — GET /api/tournaments/{tournamentId}/activity-assignments
    // -------------------------------------------------------------------------

    /**
     * Returns the computed activity assignments for the given tournament's phase.
     *
     * <p>Returns an empty (but structurally valid) response when:
     * <ul>
     *   <li>No activity types are configured (AC6)</li>
     *   <li>No phase exists or no matches are slotted (AC6 — phase not prepared)</li>
     * </ul>
     *
     * @param tournamentId the tournament UUID (from path)
     * @param phaseId      optional phase UUID (query param); if absent, auto-resolved
     * @return 200 OK with the assignment preview; 404 if tournament not found
     */
    @GetMapping
    public ResponseEntity<ActivityAssignmentPreviewResponse> getActivityAssignments(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestParam(value = "phaseId", required = false) UUID phaseId) {

        // Verify tournament exists (tenant-scoped via repository)
        tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Tournament not found: " + tournamentId));

        // Resolve phase
        Phase phase = resolvePhase(tournamentId, phaseId);

        // AC6: no phase or no slotted matches → return empty preview
        if (phase == null) {
            return ResponseEntity.ok(emptyPreview(null));
        }

        // Load matches for the phase
        List<Match> matches = matchRepository.findByPhaseId(phase.getId());

        // Determine total lap count from slotted matches (lapNumber not null)
        int totalLapCount = matches.stream()
                .filter(m -> m.getLapNumber() != null)
                .mapToInt(Match::getLapNumber)
                .max()
                .orElse(0);

        if (totalLapCount == 0) {
            // Phase exists but no slots assigned yet — matches not generated or not slotted
            return ResponseEntity.ok(emptyPreview(phase.getId()));
        }

        // Build match schedule: lapNumber → set of teamIds (playing)
        // Need TeamAvatar → teamId mapping
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phase.getId());
        Map<UUID, UUID> avatarIdToTeamId = avatars.stream()
                .collect(Collectors.toMap(TeamAvatar::getId, TeamAvatar::getTeamId));

        Map<Integer, Set<UUID>> matchSchedule = new HashMap<>();
        Map<Integer, Set<UUID>> refereeSchedule = new HashMap<>();

        for (Match m : matches) {
            if (m.getLapNumber() == null) {
                continue;  // not slotted yet — skip
            }
            int lap = m.getLapNumber();

            // Playing teams (via avatar → team mapping)
            UUID team1 = avatarIdToTeamId.get(m.getMemberAvatar1Id());
            UUID team2 = avatarIdToTeamId.get(m.getMemberAvatar2Id());
            if (team1 != null) {
                matchSchedule.computeIfAbsent(lap, k -> new HashSet<>()).add(team1);
            }
            if (team2 != null) {
                matchSchedule.computeIfAbsent(lap, k -> new HashSet<>()).add(team2);
            }

            // Referee team (null-safe — not assigned yet is valid)
            if (m.getRefereeTeamId() != null) {
                refereeSchedule.computeIfAbsent(lap, k -> new HashSet<>()).add(m.getRefereeTeamId());
            }
        }

        // All team IDs for this phase
        Set<UUID> allTeamIds = avatars.stream()
                .map(TeamAvatar::getTeamId)
                .collect(Collectors.toSet());

        // Load activity types
        List<ActivityType> activityTypes = activityTypeService.findByTournamentId(tournamentId);

        if (activityTypes.isEmpty()) {
            return ResponseEntity.ok(emptyPreview(phase.getId()));
        }

        // Compute assignments
        ActivityAssignmentResult result = activityAssignmentService.assignActivities(
                activityTypes, matchSchedule, refereeSchedule, totalLapCount, allTeamIds);

        // Build team lookup map: teamId → Team (for teamNumber and teamName in response)
        Map<UUID, Team> teamById = teamRepository.findByTournamentId(tournamentId)
                .stream()
                .collect(Collectors.toMap(Team::getId, t -> t));

        // Map result → response DTO
        return ResponseEntity.ok(mapToResponse(phase.getId(), result, activityTypes, teamById));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the phase to use for the preview.
     *
     * <p>Priority:
     * <ol>
     *   <li>If {@code phaseId} provided — load it directly (verify it belongs to the tournament).</li>
     *   <li>First ACTIVE phase (by sequenceNumber ascending).</li>
     *   <li>Last PENDING phase (by sequenceNumber descending).</li>
     *   <li>null if no suitable phase found.</li>
     * </ol>
     */
    private Phase resolvePhase(UUID tournamentId, UUID phaseId) {
        if (phaseId != null) {
            return phaseRepository.findById(phaseId)
                    .filter(p -> tournamentId.equals(p.getTournamentId()))
                    .orElse(null);
        }

        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        if (phases.isEmpty()) {
            return null;
        }

        // Prefer ACTIVE
        return phases.stream()
                .filter(p -> Phase.PhaseStatus.ACTIVE.name().equals(p.getStatus()))
                .min(Comparator.comparingInt(Phase::getSequenceNumber))
                .orElseGet(() ->
                        // Fall back to last PENDING
                        phases.stream()
                                .filter(p -> Phase.PhaseStatus.PENDING.name().equals(p.getStatus()))
                                .max(Comparator.comparingInt(Phase::getSequenceNumber))
                                .orElse(null));
    }

    /**
     * Returns an empty preview response (AC6 — no phase prepared or no activity types).
     */
    private ActivityAssignmentPreviewResponse emptyPreview(UUID phaseId) {
        return new ActivityAssignmentPreviewResponse(phaseId, List.of(), List.of());
    }

    /**
     * Maps the assignment result to the preview response DTO.
     *
     * <p>Groups assignments per activity type, then per lap.
     */
    private ActivityAssignmentPreviewResponse mapToResponse(
            UUID phaseId,
            ActivityAssignmentResult result,
            List<ActivityType> activityTypes,
            Map<UUID, Team> teamById) {

        List<ActivityTypeAssignment> assignments = new ArrayList<>();
        List<UnassignedEntry> unassigned = new ArrayList<>();

        for (ActivityType at : activityTypes) {
            List<ActivityAssignment> assigned = result.getAssignments()
                    .getOrDefault(at, List.of());

            // Group by lapNumber (preserve lap order)
            Map<Integer, List<ActivityAssignment>> byLap = new LinkedHashMap<>();
            for (ActivityAssignment aa : assigned) {
                byLap.computeIfAbsent(aa.getLapNumber(), k -> new ArrayList<>()).add(aa);
            }

            List<LapEntry> entries = byLap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new LapEntry(e.getKey(), e.getValue().stream()
                            .map(aa -> toTeamRef(aa.getTeamId(), teamById))
                            .toList()))
                    .toList();

            assignments.add(new ActivityTypeAssignment(at.getName(), entries));

            // Unassigned teams
            Set<UUID> unassignedTeamIds = result.getUnassignedTeams()
                    .getOrDefault(at, Set.of());
            List<TeamRef> unassignedRefs = unassignedTeamIds.stream()
                    .map(tid -> toTeamRef(tid, teamById))
                    .sorted(Comparator.comparingInt(TeamRef::teamNumber))
                    .toList();
            unassigned.add(new UnassignedEntry(at.getName(), unassignedRefs));
        }

        return new ActivityAssignmentPreviewResponse(phaseId, assignments, unassigned);
    }

    /**
     * Builds a {@link TeamRef} from a team ID and the tournament's team lookup map.
     *
     * <p>If the team is not found in the lookup (e.g., data inconsistency), returns a
     * placeholder with teamNumber 0 and teamName "?".
     */
    private TeamRef toTeamRef(UUID teamId, Map<UUID, Team> teamById) {
        Team team = teamById.get(teamId);
        if (team == null) {
            return new TeamRef(teamId, 0, "?");
        }
        return new TeamRef(teamId, team.getTeamNumber(), team.getDescription());
    }
}
