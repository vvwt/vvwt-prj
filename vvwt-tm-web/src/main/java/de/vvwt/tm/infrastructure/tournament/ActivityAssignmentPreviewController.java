package de.vvwt.tm.infrastructure.tournament;

import de.vvwt.tm.infrastructure.tournament.dto.ActivityAssignmentPreviewResponse;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityAssignmentPreviewResponse.ActivityTypeAssignment;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityAssignmentPreviewResponse.LapEntry;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityAssignmentPreviewResponse.TeamRef;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityAssignmentPreviewResponse.UnassignedEntry;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.activity.ActivityAssignment;
import de.vvwt.tm.tournament.activity.ActivityAssignmentResult;
import de.vvwt.tm.tournament.activity.ActivityAssignmentService;
import de.vvwt.tm.tournament.activity.ActivityType;
import de.vvwt.tm.tournament.activity.ActivityTypeService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the activity assignment preview (E20S02, AC2).
 *
 * <p>Reconstructed TDD-first under Approach C (E20 methodology). Behaviour reference: {@code
 * archive/E08S06-pre-dec28} commit {@code 23f2ffa}. New code was written test-first; no code was
 * copied from the archive.
 *
 * <h2>Endpoint</h2>
 *
 * <ul>
 *   <li>{@code GET /api/tournaments/{tournamentId}/activity-assignments} — returns computed
 *       assignments
 * </ul>
 *
 * <h2>Phase resolution (AC2, AC6)</h2>
 *
 * <p>When no {@code phaseId} query parameter is provided, the controller resolves the phase
 * automatically: ACTIVE phase preferred, else the last PENDING phase (by sequenceNumber). If no
 * suitable phase exists or no matches are slotted, returns an empty preview (not 404).
 *
 * <h2>Package placement (DEC-21 / AC11)</h2>
 *
 * <p>Located at {@code de.vvwt.tm.infrastructure.tournament} — a sub-package under the existing
 * {@code infrastructure} Spring Modulith context. No new top-level module is introduced.
 *
 * @see ActivityAssignmentPreviewResponse
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

    /**
     * Constructs an {@code ActivityAssignmentPreviewController}.
     *
     * @param tournamentRepository the tournament repository (NOT NULL)
     * @param phaseRepository the phase repository (NOT NULL)
     * @param matchRepository the match repository (NOT NULL)
     * @param teamAvatarRepository the team avatar repository (NOT NULL)
     * @param teamRepository the team repository (NOT NULL)
     * @param activityTypeService the activity type domain service (NOT NULL)
     * @param activityAssignmentService the assignment computation service (NOT NULL)
     */
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
    // GET /api/tournaments/{tournamentId}/activity-assignments
    // -------------------------------------------------------------------------

    /**
     * Returns the computed activity assignments for the given tournament's phase.
     *
     * <p>Returns an empty (but structurally valid) response when:
     *
     * <ul>
     *   <li>No activity types are configured (AC6)
     *   <li>No phase exists or no matches are slotted (AC6 — phase not prepared)
     * </ul>
     *
     * @param tournamentId the tournament UUID (from path)
     * @param phaseId optional phase UUID (query param); if absent, auto-resolved
     * @return 200 OK with the assignment preview; 404 if tournament not found
     */
    @GetMapping
    public ResponseEntity<ActivityAssignmentPreviewResponse> getActivityAssignments(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestParam(value = "phaseId", required = false) UUID phaseId) {

        // Verify tournament exists (tenant-scoped via repository)
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));

        // Resolve phase
        Phase phase = resolvePhase(tournamentId, phaseId);

        // AC6: no phase → return empty preview
        if (phase == null) {
            return ResponseEntity.ok(emptyPreview(null));
        }

        // Load matches for the phase
        List<Match> matches = matchRepository.findByPhaseId(phase.getId());

        // Determine total lap count from slotted matches (lapNumber not null)
        int totalLapCount =
                matches.stream()
                        .filter(m -> m.getLapNumber() != null)
                        .mapToInt(Match::getLapNumber)
                        .max()
                        .orElse(0);

        if (totalLapCount == 0) {
            // Phase exists but no slots assigned yet
            return ResponseEntity.ok(emptyPreview(phase.getId()));
        }

        // Build match schedule: lapNumber → set of teamIds playing
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phase.getId());
        Map<UUID, UUID> avatarIdToTeamId =
                avatars.stream()
                        .collect(Collectors.toMap(TeamAvatar::getId, TeamAvatar::getTeamId));

        Map<Integer, Set<UUID>> matchSchedule = new HashMap<>();
        Map<Integer, Set<UUID>> refereeSchedule = new HashMap<>();

        for (Match m : matches) {
            if (m.getLapNumber() == null) {
                continue;
            }
            int lap = m.getLapNumber();

            UUID team1 = avatarIdToTeamId.get(m.getMemberAvatar1Id());
            UUID team2 = avatarIdToTeamId.get(m.getMemberAvatar2Id());
            if (team1 != null) {
                matchSchedule.computeIfAbsent(lap, k -> new HashSet<>()).add(team1);
            }
            if (team2 != null) {
                matchSchedule.computeIfAbsent(lap, k -> new HashSet<>()).add(team2);
            }

            if (m.getRefereeTeamId() != null) {
                refereeSchedule
                        .computeIfAbsent(lap, k -> new HashSet<>())
                        .add(m.getRefereeTeamId());
            }
        }

        // All team IDs for this phase
        Set<UUID> allTeamIds =
                avatars.stream().map(TeamAvatar::getTeamId).collect(Collectors.toSet());

        // Load activity types
        List<ActivityType> activityTypes = activityTypeService.findByTournamentId(tournamentId);

        if (activityTypes.isEmpty()) {
            return ResponseEntity.ok(emptyPreview(phase.getId()));
        }

        // Compute assignments
        ActivityAssignmentResult result =
                activityAssignmentService.assignActivities(
                        activityTypes, matchSchedule, refereeSchedule, totalLapCount, allTeamIds);

        // Build team lookup map
        Map<UUID, Team> teamById =
                teamRepository.findByTournamentId(tournamentId).stream()
                        .collect(Collectors.toMap(Team::getId, t -> t));

        return ResponseEntity.ok(mapToResponse(phase.getId(), result, activityTypes, teamById));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the phase to use for the preview.
     *
     * <p>Priority:
     *
     * <ol>
     *   <li>If {@code phaseId} provided — load it directly (verify it belongs to the tournament).
     *   <li>First ACTIVE phase (by sequenceNumber ascending).
     *   <li>Last PENDING phase (by sequenceNumber descending).
     *   <li>{@code null} if no suitable phase found.
     * </ol>
     *
     * @param tournamentId the tournament UUID
     * @param phaseId optional explicit phase UUID
     * @return the resolved phase, or {@code null}
     */
    private Phase resolvePhase(UUID tournamentId, UUID phaseId) {
        if (phaseId != null) {
            return phaseRepository
                    .findById(phaseId)
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
                .orElseGet(
                        () ->
                                // Fall back to last PENDING or PREPARED (E48S17: PREPARED also
                                // qualifies — Display-Branch-Erweiterung Pfad α;
                                // DEC-40 Q-1a note: legacy controller, minimal touch rule applies)
                                phases.stream()
                                        .filter(p -> isPendingOrPrepared(p.getStatus()))
                                        .max(Comparator.comparingInt(Phase::getSequenceNumber))
                                        .orElse(null));
    }

    /**
     * Returns an empty preview response (AC6 — no phase prepared or no activity types).
     *
     * @param phaseId the phase UUID (may be {@code null})
     * @return an empty preview
     */
    private ActivityAssignmentPreviewResponse emptyPreview(UUID phaseId) {
        return new ActivityAssignmentPreviewResponse(phaseId, List.of(), List.of());
    }

    /**
     * Maps the assignment result to the preview response DTO.
     *
     * @param phaseId the phase UUID
     * @param result the computed assignment result
     * @param activityTypes the activity types (ordered)
     * @param teamById team lookup by ID
     * @return the response DTO
     */
    private ActivityAssignmentPreviewResponse mapToResponse(
            UUID phaseId,
            ActivityAssignmentResult result,
            List<ActivityType> activityTypes,
            Map<UUID, Team> teamById) {

        List<ActivityTypeAssignment> assignments = new ArrayList<>();
        List<UnassignedEntry> unassigned = new ArrayList<>();

        for (ActivityType at : activityTypes) {
            List<ActivityAssignment> assigned = result.getAssignments().getOrDefault(at, List.of());

            // Group by lapNumber (preserve lap order)
            Map<Integer, List<ActivityAssignment>> byLap = new LinkedHashMap<>();
            for (ActivityAssignment aa : assigned) {
                byLap.computeIfAbsent(aa.getLapNumber(), k -> new ArrayList<>()).add(aa);
            }

            List<LapEntry> entries =
                    byLap.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(
                                    e ->
                                            new LapEntry(
                                                    e.getKey(),
                                                    e.getValue().stream()
                                                            .map(
                                                                    aa ->
                                                                            toTeamRef(
                                                                                    aa.getTeamId(),
                                                                                    teamById))
                                                            .toList()))
                            .toList();

            assignments.add(new ActivityTypeAssignment(at.getName(), entries));

            // Unassigned teams
            Set<UUID> unassignedTeamIds = result.getUnassignedTeams().getOrDefault(at, Set.of());
            List<TeamRef> unassignedRefs =
                    unassignedTeamIds.stream()
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
     * <p>If the team is not found in the lookup (e.g., data inconsistency), returns a placeholder
     * with teamNumber 0 and teamName "?".
     *
     * @param teamId the team UUID
     * @param teamById team lookup by ID
     * @return the team reference
     */
    private TeamRef toTeamRef(UUID teamId, Map<UUID, Team> teamById) {
        Team team = teamById.get(teamId);
        if (team == null) {
            return new TeamRef(teamId, 0, "?");
        }
        return new TeamRef(teamId, team.getTeamNumber(), team.getDescription());
    }

    /**
     * Returns {@code true} if the given phase status is {@code PENDING} or {@code PREPARED}.
     *
     * <p>E48S17 Display-Branch-Erweiterung Pfad α: PREPARED phases now qualify as the preview
     * fallback alongside PENDING.
     *
     * <p>DEC-40 Q-1a note: this method is added as a minimal-touch change to this legacy
     * controller. Full DEC-40 reconstruction of {@code ActivityAssignmentPreviewController} is
     * deferred to a future Epic per the reconstruction-in-place plan.
     *
     * @param status the phase status string
     * @return {@code true} for PENDING or PREPARED; {@code false} otherwise
     * @see <a href="E48S17">E48S17 — AC-IMPL-DISPLAY-PREPARED-PHASE-FALLBACK</a>
     */
    private static boolean isPendingOrPrepared(String status) {
        return Phase.PhaseStatus.PENDING.name().equals(status)
                || Phase.PhaseStatus.PREPARED.name().equals(status);
    }
}
