package de.vvwt.tm.domain;

import de.vvwt.tm.domain.referee.RefereeAssigner;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Domain service for referee assignment operations on a phase (E05S09).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>{@link #getAssignments(UUID)} — returns all matches in the phase with their referee
 *       assignments, eligible referee teams list, and per-team summary (AC1, AC7, AC9).</li>
 *   <li>{@link #overrideReferee(UUID, UUID, UUID)} — sets a manual referee override for a
 *       specific match, validating the "team cannot referee while playing" constraint (AC2, AC8).</li>
 *   <li>{@link #clearRefereeOverride(UUID, UUID)} — clears a manual override and sets the
 *       match referee fields to null (AC3).</li>
 *   <li>{@link #reassignAll(UUID)} — re-runs the auto-assignment algorithm for all
 *       non-manually-overridden matches; manual overrides are preserved (AC4).</li>
 * </ul>
 *
 * <h2>Manual override convention</h2>
 * <p>A match is considered "manually overridden" when its {@code refereeDescription} field is
 * set to the sentinel value {@code "MANUAL"}. This is consistent with the existing
 * {@link RefereeAssigner} logic which treats any non-null {@code refereeDescription} as a
 * manual override to preserve.
 *
 * <h2>Tenant scope (DEC-5, DEC-17, AC11)</h2>
 * <p>All repository calls are tenant-scoped via {@link de.vvwt.tm.domain.repo.TenantScopedRepository}.
 * The service performs an explicit tenant ownership check when loading a phase via
 * {@link #requirePhaseWithTenantScope(UUID)}: it verifies that the phase's tournament belongs to
 * the active tenant and throws {@link NoSuchElementException} if not — this appears as 404 at
 * the REST layer, preventing information leakage across tenants.
 *
 * @see RefereeAssigner
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S09.story.md">Story E05S09</a>
 */
@Service
public class RefereeAssignmentService {

    private static final Logger LOG = LoggerFactory.getLogger(RefereeAssignmentService.class);

    /** Sentinel value stored in {@code match.refereeDescription} to mark a manual override. */
    public static final String MANUAL_OVERRIDE_SENTINEL = "MANUAL";

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;
    private final RefereeAssigner refereeAssigner;

    public RefereeAssignmentService(PhaseRepository phaseRepository,
                                     MatchRepository matchRepository,
                                     TeamAvatarRepository teamAvatarRepository,
                                     TeamRepository teamRepository,
                                     TournamentRepository tournamentRepository,
                                     RefereeAssigner refereeAssigner) {
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamRepository = teamRepository;
        this.tournamentRepository = tournamentRepository;
        this.refereeAssigner = refereeAssigner;
    }

    // =========================================================================
    // AC1 — getAssignments
    // =========================================================================

    /**
     * Returns the complete referee assignment overview for the given phase (AC1, AC7, AC9).
     *
     * <p>Includes:
     * <ul>
     *   <li>All matches with their current referee assignment and isManualOverride flag (AC1)</li>
     *   <li>Per-team assignment count summary (AC7)</li>
     *   <li>All eligible referee teams in the phase (teams with refereeAssignment=true) with their
     *       avatar IDs for client-side filtering and override submission (AC6)</li>
     * </ul>
     *
     * @param phaseId the phase to query (tenant-scoped)
     * @return the assignment overview; never {@code null}
     * @throws NoSuchElementException if the phase does not exist or does not belong to this tenant
     */
    @Transactional(readOnly = true)
    public RefereeAssignmentOverview getAssignments(UUID phaseId) {
        Phase phase = requirePhaseWithTenantScope(phaseId);

        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        List<Team> teams = teamRepository.findByTournamentId(phase.getTournamentId());

        Map<UUID, TeamAvatar> avatarById = buildAvatarIndex(avatars);
        Map<UUID, Team> teamById = buildTeamIndex(teams);
        // avatarId → teamId for reverse-lookup (which teamId does a given avatarId represent)
        Map<UUID, UUID> avatarIdToTeamId = buildAvatarToTeamIndex(avatars);
        // teamId → avatarId in this phase (for referee dropdown)
        Map<UUID, UUID> teamIdToAvatarId = buildTeamToAvatarIndex(avatars);

        // Build assignment entries sorted by lapNumber, then fieldNumber
        List<RefereeAssignmentEntry> entries = buildAssignmentEntries(
                matches, avatarById, teamById, avatarIdToTeamId, teamIdToAvatarId);

        // Build per-team assignment summary (AC7)
        List<TeamAssignmentSummary> summary = buildTeamAssignmentSummary(matches, teamById);

        // Build eligible referee team options (all teams with refereeAssignment=true in this phase)
        List<RefereeTeamOption> refereeTeamOptions = buildRefereeTeamOptions(teams, teamIdToAvatarId);

        LOG.debug("getAssignments: phaseId={} entries={} refereeTeams={}",
                phaseId, entries.size(), refereeTeamOptions.size());

        return new RefereeAssignmentOverview(entries, summary, refereeTeamOptions);
    }

    // =========================================================================
    // AC2 — overrideReferee
    // =========================================================================

    /**
     * Sets a manual referee override for the given match (AC2, AC8).
     *
     * <p>Validates the hard constraint: the assigned team cannot be playing in the same lap.
     * Throws {@link ConflictException} (409) if the constraint is violated.
     *
     * <p>The {@code refereeTeamAvatarId} is the avatar ID of the team in this phase (per DEC-9
     * structural identity). The service resolves it to a {@code teamId} before persisting.
     *
     * @param phaseId              the phase containing the match (tenant-scoped)
     * @param matchId              the match to override
     * @param refereeTeamAvatarId  the avatar ID of the team to assign as referee
     * @return the updated assignment entry
     * @throws NoSuchElementException if the phase, match, or avatar is not found
     * @throws ConflictException      if the assigned team is playing in the same lap
     */
    @Transactional
    public RefereeAssignmentEntry overrideReferee(UUID phaseId, UUID matchId, UUID refereeTeamAvatarId) {
        Phase phase = requirePhaseWithTenantScope(phaseId);

        // Load and validate the match belongs to this phase
        Match match = requireMatchInPhase(matchId, phaseId);

        // Resolve avatar → teamId
        TeamAvatar refereeAvatar = teamAvatarRepository.findById(refereeTeamAvatarId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Team avatar not found: " + refereeTeamAvatarId));
        if (!phaseId.equals(refereeAvatar.getPhaseId())) {
            throw new NoSuchElementException(
                    "Team avatar " + refereeTeamAvatarId + " does not belong to phase " + phaseId);
        }

        UUID refereeTeamId = refereeAvatar.getTeamId();

        // Validate hard constraint: the referee team must not be playing in the same lap (AC2, AC8)
        int lapNumber = match.getLapNumber() != null ? match.getLapNumber() : 0;
        validateNotPlayingInLap(phaseId, refereeTeamId, lapNumber, matchId);

        // Apply override (AC2)
        match.setRefereeTeamId(refereeTeamId);
        match.setRefereeDescription(MANUAL_OVERRIDE_SENTINEL);
        matchRepository.save(match);

        LOG.info("overrideReferee: phaseId={} matchId={} refereeTeamId={} (manual override)",
                phaseId, matchId, refereeTeamId);

        // Return updated entry
        return buildSingleEntry(match, phase, refereeAvatar.getId());
    }

    // =========================================================================
    // AC3 — clearRefereeOverride
    // =========================================================================

    /**
     * Clears a manual referee override for the given match, setting both
     * {@code refereeTeamId} and {@code refereeDescription} to null (AC3).
     *
     * @param phaseId the phase containing the match (tenant-scoped)
     * @param matchId the match whose override to clear
     * @return the updated assignment entry (refereeTeamId and refereeTeamName will be null)
     * @throws NoSuchElementException if the phase or match is not found
     */
    @Transactional
    public RefereeAssignmentEntry clearRefereeOverride(UUID phaseId, UUID matchId) {
        requirePhaseWithTenantScope(phaseId);

        Match match = requireMatchInPhase(matchId, phaseId);

        match.setRefereeTeamId(null);
        match.setRefereeDescription(null);
        matchRepository.save(match);

        LOG.info("clearRefereeOverride: phaseId={} matchId={} — referee cleared", phaseId, matchId);

        return buildSingleEntry(match, null, null);
    }

    // =========================================================================
    // AC4 — reassignAll
    // =========================================================================

    /**
     * Re-runs the auto-assignment algorithm for all non-manually-overridden matches in the phase.
     * Manual overrides (refereeDescription = "MANUAL") are preserved (AC4).
     *
     * @param phaseId the phase to re-assign (tenant-scoped)
     * @return the updated assignment overview
     * @throws NoSuchElementException if the phase does not exist or does not belong to this tenant
     */
    @Transactional
    public RefereeAssignmentOverview reassignAll(UUID phaseId) {
        requirePhaseWithTenantScope(phaseId);

        LOG.info("reassignAll: phaseId={} — running auto-assignment (manual overrides preserved)", phaseId);

        // RefereeAssigner already preserves manual overrides (refereeDescription != null)
        refereeAssigner.assignReferees(phaseId);

        // Return the updated overview
        return getAssignments(phaseId);
    }

    // =========================================================================
    // Domain records
    // =========================================================================

    /**
     * Full assignment overview returned by AC1 and AC4.
     *
     * @param assignments       per-match assignment entries
     * @param teamSummary       per-team assignment count summary (AC7)
     * @param allRefereeTeams   eligible referee teams in this phase (AC6)
     */
    public record RefereeAssignmentOverview(
            List<RefereeAssignmentEntry> assignments,
            List<TeamAssignmentSummary> teamSummary,
            List<RefereeTeamOption> allRefereeTeams
    ) {}

    /**
     * Single match referee assignment entry.
     *
     * @param matchId           the match UUID
     * @param lapNumber         the lap number (null if slots not assigned)
     * @param fieldNumber       the field number (null if slots not assigned)
     * @param team1Description  display name for team 1
     * @param team2Description  display name for team 2
     * @param refereeTeamName   display name for the referee team (null if unassigned)
     * @param refereeTeamId     UUID of the referee team (null if unassigned)
     * @param refereeTeamAvatarId  avatar ID of the referee team in this phase (null if unassigned
     *                             or if the team has no avatar in this phase)
     * @param isManualOverride  true if refereeDescription equals {@link #MANUAL_OVERRIDE_SENTINEL}
     */
    public record RefereeAssignmentEntry(
            UUID matchId,
            Integer lapNumber,
            Integer fieldNumber,
            String team1Description,
            String team2Description,
            String refereeTeamName,
            UUID refereeTeamId,
            UUID refereeTeamAvatarId,
            boolean isManualOverride
    ) {}

    /**
     * Per-team assignment count summary (AC7).
     *
     * @param teamId        the team UUID
     * @param teamName      the team display name
     * @param assignedCount number of matches this team is assigned to referee
     */
    public record TeamAssignmentSummary(
            UUID teamId,
            String teamName,
            int assignedCount
    ) {}

    /**
     * Eligible referee team option for the override dropdown (AC6).
     *
     * @param avatarId  the avatar ID of this team in the phase (submitted as refereeTeamAvatarId)
     * @param teamId    the underlying team UUID
     * @param teamName  display name
     */
    public record RefereeTeamOption(
            UUID avatarId,
            UUID teamId,
            String teamName
    ) {}

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Map<UUID, TeamAvatar> buildAvatarIndex(List<TeamAvatar> avatars) {
        Map<UUID, TeamAvatar> index = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar a : avatars) {
            index.put(a.getId(), a);
        }
        return index;
    }

    private Map<UUID, Team> buildTeamIndex(List<Team> teams) {
        Map<UUID, Team> index = new HashMap<>(teams.size() * 2);
        for (Team t : teams) {
            index.put(t.getId(), t);
        }
        return index;
    }

    private Map<UUID, UUID> buildAvatarToTeamIndex(List<TeamAvatar> avatars) {
        Map<UUID, UUID> index = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar a : avatars) {
            index.put(a.getId(), a.getTeamId());
        }
        return index;
    }

    private Map<UUID, UUID> buildTeamToAvatarIndex(List<TeamAvatar> avatars) {
        // If a team appears multiple times (should not happen per DEC-9 but defensive),
        // keep the last avatar. One team → one avatar per phase by schema constraint.
        Map<UUID, UUID> index = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar a : avatars) {
            index.put(a.getTeamId(), a.getId());
        }
        return index;
    }

    private List<RefereeAssignmentEntry> buildAssignmentEntries(
            List<Match> matches,
            Map<UUID, TeamAvatar> avatarById,
            Map<UUID, Team> teamById,
            Map<UUID, UUID> avatarIdToTeamId,
            Map<UUID, UUID> teamIdToAvatarId) {

        return matches.stream()
                .sorted(Comparator
                        .comparingInt((Match m) -> m.getLapNumber() != null ? m.getLapNumber() : Integer.MAX_VALUE)
                        .thenComparingInt(m -> m.getFieldNumber() != null ? m.getFieldNumber() : Integer.MAX_VALUE))
                .map(match -> {
                    String team1Desc = resolveAvatarDescription(avatarById.get(match.getMemberAvatar1Id()));
                    String team2Desc = resolveAvatarDescription(avatarById.get(match.getMemberAvatar2Id()));

                    UUID refereeTeamId = match.getRefereeTeamId();
                    String refereeTeamName = null;
                    UUID refereeTeamAvatarId = null;
                    if (refereeTeamId != null) {
                        Team refereeTeam = teamById.get(refereeTeamId);
                        refereeTeamName = refereeTeam != null ? refereeTeam.getDescription() : "Unknown";
                        refereeTeamAvatarId = teamIdToAvatarId.get(refereeTeamId);
                    }

                    boolean isManualOverride = MANUAL_OVERRIDE_SENTINEL.equals(match.getRefereeDescription());

                    return new RefereeAssignmentEntry(
                            match.getId(),
                            match.getLapNumber(),
                            match.getFieldNumber(),
                            team1Desc,
                            team2Desc,
                            refereeTeamName,
                            refereeTeamId,
                            refereeTeamAvatarId,
                            isManualOverride
                    );
                })
                .collect(Collectors.toList());
    }

    private List<TeamAssignmentSummary> buildTeamAssignmentSummary(
            List<Match> matches, Map<UUID, Team> teamById) {

        // Count assignments per team
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Match m : matches) {
            if (m.getRefereeTeamId() != null) {
                counts.merge(m.getRefereeTeamId(), 1, Integer::sum);
            }
        }

        return counts.entrySet().stream()
                .sorted(Map.Entry.<UUID, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> {
                    Team team = teamById.get(entry.getKey());
                    String name = team != null ? team.getDescription() : "Unknown";
                    return new TeamAssignmentSummary(entry.getKey(), name, entry.getValue());
                })
                .collect(Collectors.toList());
    }

    private List<RefereeTeamOption> buildRefereeTeamOptions(
            List<Team> teams, Map<UUID, UUID> teamIdToAvatarId) {

        return teams.stream()
                .filter(Team::isRefereeAssignment)
                .filter(t -> teamIdToAvatarId.containsKey(t.getId()))  // only teams with avatars in this phase
                .sorted(Comparator.comparingInt(Team::getTeamNumber))
                .map(t -> new RefereeTeamOption(
                        teamIdToAvatarId.get(t.getId()),
                        t.getId(),
                        t.getDescription()))
                .collect(Collectors.toList());
    }

    /**
     * Validates that the given team is not playing in the given lap (AC2, AC8).
     *
     * @param phaseId       the phase
     * @param teamId        the team to check
     * @param lapNumber     the lap to check
     * @param targetMatchId the match being overridden (excluded from the playing check to
     *                      allow re-assigning a team already set on this match)
     * @throws ConflictException if the team is playing in the given lap
     */
    private void validateNotPlayingInLap(UUID phaseId, UUID teamId, int lapNumber, UUID targetMatchId) {
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);

        // Find avatar IDs for this team in this phase
        Set<UUID> teamAvatarIds = new HashSet<>();
        for (TeamAvatar avatar : avatars) {
            if (teamId.equals(avatar.getTeamId())) {
                teamAvatarIds.add(avatar.getId());
            }
        }

        if (teamAvatarIds.isEmpty()) {
            // Team has no avatar in this phase — cannot be playing → constraint satisfied
            return;
        }

        // Check if any match in this lap has this team as a participant
        List<Match> allMatches = matchRepository.findByPhaseId(phaseId);
        for (Match match : allMatches) {
            if (targetMatchId.equals(match.getId())) {
                // Skip the target match itself — allows re-assigning without triggering constraint
                continue;
            }
            if (lapNumber != (match.getLapNumber() != null ? match.getLapNumber() : -1)) {
                continue;
            }
            if (teamAvatarIds.contains(match.getMemberAvatar1Id())
                    || teamAvatarIds.contains(match.getMemberAvatar2Id())) {
                // Resolve team name for the error message
                Team team = teamRepository.findByTournamentId(
                        phaseRepository.findById(phaseId)
                                .map(Phase::getTournamentId)
                                .orElse(UUID.randomUUID()))
                        .stream()
                        .filter(t -> teamId.equals(t.getId()))
                        .findFirst()
                        .orElse(null);
                String teamName = team != null ? team.getDescription() : teamId.toString();
                throw new ConflictException(
                        "Team \"" + teamName + "\" is playing in lap " + lapNumber
                        + " and cannot also serve as referee in the same lap.");
            }
        }
    }

    private RefereeAssignmentEntry buildSingleEntry(Match match, Phase phase, UUID refereeTeamAvatarId) {
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(match.getPhaseId());
        Map<UUID, TeamAvatar> avatarById = buildAvatarIndex(avatars);

        String team1Desc = resolveAvatarDescription(avatarById.get(match.getMemberAvatar1Id()));
        String team2Desc = resolveAvatarDescription(avatarById.get(match.getMemberAvatar2Id()));

        String refereeTeamName = null;
        UUID resolvedRefereeTeamAvatarId = refereeTeamAvatarId;
        if (match.getRefereeTeamId() != null && phase != null) {
            List<Team> teams = teamRepository.findByTournamentId(phase.getTournamentId());
            for (Team t : teams) {
                if (match.getRefereeTeamId().equals(t.getId())) {
                    refereeTeamName = t.getDescription();
                    break;
                }
            }
            if (resolvedRefereeTeamAvatarId == null) {
                Map<UUID, UUID> teamToAvatar = buildTeamToAvatarIndex(avatars);
                resolvedRefereeTeamAvatarId = teamToAvatar.get(match.getRefereeTeamId());
            }
        }

        boolean isManualOverride = MANUAL_OVERRIDE_SENTINEL.equals(match.getRefereeDescription());

        return new RefereeAssignmentEntry(
                match.getId(),
                match.getLapNumber(),
                match.getFieldNumber(),
                team1Desc,
                team2Desc,
                refereeTeamName,
                match.getRefereeTeamId(),
                resolvedRefereeTeamAvatarId,
                isManualOverride
        );
    }

    private static String resolveAvatarDescription(TeamAvatar avatar) {
        if (avatar == null) {
            return "Unknown";
        }
        return avatar.getDescription() != null ? avatar.getDescription() : avatar.getId().toString();
    }

    private Match requireMatchInPhase(UUID matchId, UUID phaseId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new NoSuchElementException("Match not found: " + matchId));
        if (!phaseId.equals(match.getPhaseId())) {
            throw new NoSuchElementException(
                    "Match " + matchId + " does not belong to phase " + phaseId);
        }
        return match;
    }

    private Phase requirePhaseWithTenantScope(UUID phaseId) {
        Phase phase = phaseRepository.findById(phaseId)
                .orElseThrow(() -> new NoSuchElementException("Phase not found: " + phaseId));
        // Tenant scope check (AC11): verify the phase's tournament belongs to active tenant
        tournamentRepository.findById(phase.getTournamentId())
                .orElseThrow(() -> new NoSuchElementException("Phase not found: " + phaseId));
        return phase;
    }
}
