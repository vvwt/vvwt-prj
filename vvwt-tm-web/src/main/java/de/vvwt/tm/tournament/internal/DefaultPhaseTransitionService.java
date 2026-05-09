package de.vvwt.tm.tournament.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.Phase.PhaseStatus;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.PhaseTransitionService;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.internal.referee.RefereeAssigner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link PhaseTransitionService} (DEC-35, E48S07, E48S20).
 *
 * <p>Implements the generic Phase N → N+1 transition workflow:
 *
 * <ul>
 *   <li>{@link #proposeTransition(UUID)} — pure read-only; no lock; no persistence. Derives
 *       team-to-(group, position) assignment from {@code toPhase}'s sortType. For Phase 1
 *       (sequenceNumber=1), uses the Phase-1-Branch (E48S18): loads {@code tournament.Teams} where
 *       {@code participate=true}, sorted by {@code teamNumber}, and distributes via Round-Robin.
 *       For Phase 2+ (sequenceNumber&gt;1), uses the existing Phase-N-Avatar path.
 *   <li>{@link #commitTransition(UUID, List)} — acquires per-tournament pessimistic DB row-lock
 *       (DEC-37 Clause B), persists {@link TeamAvatar} entities for {@code toPhaseId}, then invokes
 *       match generation via {@link PhasePreparationService#generateMatches}.
 * </ul>
 *
 * <h2>E48S20 — DTO widening (AC-IMPL-SERVICE-POPULATES-FIELDS)</h2>
 *
 * <p>All {@code computeXxx} methods populate the four new {@link TeamAvatarProposal} display fields
 * ({@code teamNumber}, {@code teamDescription}, {@code sourceGroupNumber}, {@code
 * sourceGroupPosition}):
 *
 * <ul>
 *   <li>Phase 1 branch: {@code teamNumber} and {@code teamDescription} from {@link Team}; source
 *       fields are {@code null} (no previous phase).
 *   <li>Phase 2+ branches: {@code teamNumber} and {@code teamDescription} from the {@link Team}
 *       aggregate behind the previous-phase {@link TeamAvatar} (via {@code
 *       TeamRepository.findById}); {@code sourceGroupNumber} and {@code sourceGroupPosition} from
 *       the fromPhase {@link TeamAvatar}'s structural identity (the team's placement in Phase N
 *       becomes the source slot for Phase N+1).
 * </ul>
 *
 * <p>Defense: if a Team cannot be resolved (corrupt data), {@link
 * #requireTeamForDisplay(TeamAvatar)} throws {@link IllegalStateException} with the offending
 * teamId (AC-ERROR-MISSING-TEAM-DEFENSE).
 *
 * <h2>sortType algorithms</h2>
 *
 * <ul>
 *   <li>{@code team_number} (Phase 1) — Round-Robin distribution over participating Tournament
 *       Teams sorted by {@code teamNumber} ascending across N target groups.
 *   <li>{@code team_number} (Phase 2+) — Round-Robin distribution over the avatar list sorted by
 *       (group_number, group_position) ascending (fromPhase seeding order) across N target groups.
 *   <li>{@code placement_group} — Teams keep their Phase-N group; positions are re-assigned by
 *       descending points (higher points = better placement = lower position number).
 *   <li>{@code group_placement} — Cross-group: rank-1 from every Phase-N group → target group 1,
 *       rank-2 → group 2, etc. Truncates to the minimum group size when groups are unequal.
 * </ul>
 *
 * @see PhaseTransitionService
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity (groupNumber, groupPosition)</a>
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in .internal</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock
 *     (commitTransition)</a>
 * @see <a href="E48S07">E48S07 — Drag&amp;Drop Phase-Transition Backend</a>
 * @see <a href="E48S18">E48S18 — Phase-1-Branch (proposeTransition for sequenceNumber=1)</a>
 * @see <a href="E48S20">E48S20 — DTO widening: display fields + source-slot fields</a>
 */
@Service("tmPhaseTransitionService")
public class DefaultPhaseTransitionService implements PhaseTransitionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPhaseTransitionService.class);

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamAvatarRatingRepository teamAvatarRatingRepository;
    private final ObjectMapper objectMapper;
    private final TeamRepository teamRepository;
    private final RefereeAssigner refereeAssigner;
    private final PhaseLifecycleService phaseLifecycleService;

    public DefaultPhaseTransitionService(
            @Qualifier("tmTournamentRepository") TournamentRepository tournamentRepository,
            @Qualifier("tmPhaseRepository") PhaseRepository phaseRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamAvatarRatingRepository teamAvatarRatingRepository,
            ObjectMapper objectMapper,
            @Qualifier("tmTeamRepository") TeamRepository teamRepository,
            @Qualifier("tmRefereeAssigner") RefereeAssigner refereeAssigner,
            @Qualifier("tmPhaseLifecycleService") PhaseLifecycleService phaseLifecycleService) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamAvatarRatingRepository = teamAvatarRatingRepository;
        this.objectMapper = objectMapper;
        this.teamRepository = teamRepository;
        this.refereeAssigner = refereeAssigner;
        this.phaseLifecycleService = phaseLifecycleService;
    }

    // -------------------------------------------------------------------------
    // proposeTransition — read-only, no lock, no persistence
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>No DB lock required. For Phase 1 (sequenceNumber=1), uses the Phase-1-Branch (E48S18):
     * loads participating Tournament Teams, sorted by teamNumber, distributes via Round-Robin. For
     * Phase 2+ (sequenceNumber&gt;1), resolves fromPhase via {@code
     * phaseRepository.findByTournamentIdAndSequenceNumber(toPhase.tournamentId,
     * toPhase.sequenceNumber - 1)} and uses existing avatar-based algorithms.
     */
    @Override
    public List<TeamAvatarProposal> proposeTransition(UUID toPhaseId) {
        Phase toPhase = requirePhase(toPhaseId);
        Tournament tournament = requireTournament(toPhase.getTournamentId());
        DraftSection toSection = resolveDraftSection(tournament, toPhase.getSequenceNumber());

        Optional<Phase> fromPhaseOpt = requireFromPhase(toPhase);
        if (fromPhaseOpt.isEmpty()) {
            // Phase-1-Branch (E48S18): source is tournament.Teams where participate=true
            return computePhase1Proposals(tournament, toSection);
        }

        // Phase N+1 branch: use fromPhase TeamAvatars (existing behavior — E48S07)
        List<TeamAvatar> fromAvatars =
                teamAvatarRepository.findByPhaseId(fromPhaseOpt.get().getId());
        // E48S20: build Team lookup map for display-field population (avoid N+1 per avatar)
        Map<UUID, Team> teamById = buildTeamLookup(fromAvatars);
        return computeProposals(fromAvatars, toSection, teamById);
    }

    // -------------------------------------------------------------------------
    // commitTransition — DEC-37 lock + persist + match generation
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>DEC-37 Clause B: acquires per-tournament pessimistic DB row-lock via {@link
     * TournamentRepository#findByIdForUpdate(UUID)} as the FIRST read.
     *
     * <p>E51S06 refactor (DEC-55 D-10): replaces avatar-INSERT loop with structural FIND+UPDATE —
     * the pre-existing placeholder avatars (created by E51S02 DraftConfig-Apply) are located by
     * (phaseId, groupNumber, groupPosition) and their {@code teamId} is updated. No new avatar rows
     * are created. Match generation is removed from this method (matches were generated by the
     * E51S03 background pipeline before this call). After all teamId-UPDATEs, referee assignment is
     * performed and the phase is transitioned from PREPARED to ASSIGNED.
     *
     * @throws IllegalStateException if no structural placeholder avatar exists for a slot (DEC-9
     *     identity must be pre-created by E51S02 before calling commitTransition)
     */
    @Override
    @Transactional
    public void commitTransition(UUID toPhaseId, List<TeamAvatarProposal> assignments) {
        // Initial read to get the tournamentId (needed for the lock)
        Phase toPhase = requirePhase(toPhaseId);

        // DEC-37 Clause B: acquire per-tournament row-lock BEFORE reading or writing mutable state.
        tournamentRepository.findByIdForUpdate(toPhase.getTournamentId());

        // Re-read phase under lock for fresh state
        toPhase = requirePhase(toPhaseId);
        Tournament tournament = requireTournament(toPhase.getTournamentId());
        DraftSection toSection = resolveDraftSection(tournament, toPhase.getSequenceNumber());

        validateAssignments(assignments, toSection);

        // E51S06 / DEC-55 D-10: UPDATE existing structural placeholder avatars (no INSERT).
        // Placeholders were created by E51S02 DraftConfig-Apply with teamId=NULL.
        // Locate each by structural identity (phaseId, groupNumber, groupPosition) and update
        // teamId.
        for (TeamAvatarProposal proposal : assignments) {
            TeamAvatar avatar =
                    teamAvatarRepository
                            .findByPhaseIdAndGroupNumberAndGroupPosition(
                                    toPhaseId, proposal.groupNumber(), proposal.groupPosition())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "No structural placeholder avatar found for"
                                                            + " phaseId="
                                                            + toPhaseId
                                                            + ", groupNumber="
                                                            + proposal.groupNumber()
                                                            + ", groupPosition="
                                                            + proposal.groupPosition()
                                                            + " — ensure DraftConfig-Apply (E51S02)"
                                                            + " was run before commitTransition"
                                                            + " (AC-ERROR-HANDLING-AVATAR-NOT-FOUND,"
                                                            + " E51S06)"));
            avatar.setTeamId(proposal.teamId());
            teamAvatarRepository.updateTeamId(avatar);
        }

        log.debug(
                "[E51S06] commitTransition: phase={}, avatar teamIds updated={}",
                toPhaseId,
                assignments.size());

        // Assign referees to all eligible matches in the phase (E51S06 / DEC-55 D-10)
        refereeAssigner.assignReferees(toPhaseId);

        log.debug("[E51S06] commitTransition: phase={}, referee assignment complete", toPhaseId);

        // Transition phase from PREPARED → ASSIGNED (E51S06 / DEC-55 D-10)
        phaseLifecycleService.transition(toPhaseId, PhaseStatus.ASSIGNED, "assign");

        log.debug(
                "[E51S06] commitTransition: phase={}, status transitioned to ASSIGNED", toPhaseId);
    }

    // -------------------------------------------------------------------------
    // Phase-1-Branch algorithm (E48S18 + E48S20 display-field population)
    // -------------------------------------------------------------------------

    /**
     * Computes a Round-Robin proposal for Phase 1 from participating Tournament Teams.
     *
     * <p>Source: {@link TeamRepository#findByTournamentId(UUID)} filtered for {@code
     * participate=true}, sorted by {@code teamNumber} ascending (repository contract).
     *
     * <p>Round-Robin: team at index {@code i} (0-indexed) goes to group {@code (i % groupCount) +
     * 1} with position {@code (i / groupCount) + 1}.
     *
     * <p>E48S20 (AC-IMPL-SERVICE-POPULATES-FIELDS): {@code teamNumber} and {@code teamDescription}
     * are taken directly from the {@link Team} entity. Source fields ({@code sourceGroupNumber},
     * {@code sourceGroupPosition}) are {@code null} — Phase 1 has no previous phase.
     *
     * @param tournament the tournament containing the participating teams
     * @param toSection the DraftSection for Phase 1 (must have sortType=team_number, E48S16
     *     invariant)
     * @return list of TeamAvatarProposals for Phase 1 (never null, never empty)
     * @throws IllegalStateException if sortType ≠ team_number (defense-in-depth vs. E48S16 bypass)
     * @throws IllegalArgumentException if no participating teams exist
     *     (AC-ERROR-HANDLING-EMPTY-TEAMS)
     * @throws IllegalStateException if any participating Team has null teamnumber or description
     *     (AC-ERROR-MISSING-TEAM-DEFENSE)
     */
    private List<TeamAvatarProposal> computePhase1Proposals(
            Tournament tournament, DraftSection toSection) {
        // Defense-in-depth: Phase 1 MUST have sortType=team_number (E48S16 invariant)
        if (!"team_number".equals(toSection.getSortType())) {
            throw new IllegalStateException(
                    "Phase 1 must have sortType=team_number, got: "
                            + toSection.getSortType()
                            + " — check tournament draft_json"
                            + " (AC-TEST-FIRST-PHASE-WRONG-SORTTYPE-RED)");
        }

        // Load all teams for this tournament (ordered by teamNumber ASC per repository contract)
        List<Team> allTeams = teamRepository.findByTournamentId(tournament.getId());

        // Filter: only participating teams
        // (AC-TEST-PROPOSE-TRANSITION-NON-PARTICIPATING-EXCLUDED-RED)
        List<Team> participating = new ArrayList<>();
        for (Team team : allTeams) {
            if (team.isParticipate()) {
                participating.add(team);
            }
        }

        if (participating.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tournament has no participating teams — register teams first"
                            + " (AC-ERROR-HANDLING-EMPTY-TEAMS, tournamentId="
                            + tournament.getId()
                            + ")");
        }

        int groupCount = toSection.getGroupCount();
        List<TeamAvatarProposal> proposals = new ArrayList<>(participating.size());
        for (int i = 0; i < participating.size(); i++) {
            Team team = participating.get(i);
            int targetGroup = (i % groupCount) + 1;
            int targetPosition = (i / groupCount) + 1;

            // E48S20 (AC-ERROR-MISSING-TEAM-DEFENSE): defense against corrupt data
            validateTeamDisplayFields(team);

            // E48S20 (AC-IMPL-SERVICE-POPULATES-FIELDS): populate display fields
            // Phase 1 has no source phase → sourceGroupNumber and sourceGroupPosition are null
            // E51S13 (AC-IMPL-DTO-SORTTYPE-NULLABLE): populate sortType from toSection (always
            // "team_number" for Phase 1 per the defense-in-depth check above)
            proposals.add(
                    new TeamAvatarProposal(
                            team.getId(),
                            team.getTeamNumber(),
                            team.getDescription(),
                            targetGroup,
                            targetPosition,
                            null,
                            null,
                            toSection.getSortType()));
        }

        log.debug(
                "[E48S18] computePhase1Proposals: tournamentId={}, participatingTeams={},"
                        + " groups={}",
                tournament.getId(),
                participating.size(),
                groupCount);

        return proposals;
    }

    // -------------------------------------------------------------------------
    // sortType algorithms (Phase 2+ — with Team lookup for display fields)
    // -------------------------------------------------------------------------

    private List<TeamAvatarProposal> computeProposals(
            List<TeamAvatar> fromAvatars, DraftSection toSection, Map<UUID, Team> teamById) {
        // E51S13: pass sortType down to sub-methods so every proposal carries it
        String sortType = toSection.getSortType();
        return switch (sortType) {
            case "team_number" ->
                    computeTeamNumber(fromAvatars, toSection.getGroupCount(), teamById, sortType);
            case "placement_group" -> computePlacementGroup(fromAvatars, teamById, sortType);
            case "group_placement" -> computeGroupPlacement(fromAvatars, teamById, sortType);
            default -> throw new IllegalArgumentException("Unknown sortType: " + sortType);
        };
    }

    /**
     * Round-Robin distribution: avatars sorted by (group_number, group_position) ascending are
     * distributed across {@code groupCount} groups in round-robin order.
     *
     * <p>Avatar at index i (0-indexed) goes to group {@code (i % groupCount) + 1} with position
     * {@code (i / groupCount) + 1}.
     *
     * <p>E48S20 (AC-IMPL-SERVICE-POPULATES-FIELDS): {@code teamNumber} and {@code teamDescription}
     * from the Team aggregate; {@code sourceGroupNumber} and {@code sourceGroupPosition} from the
     * fromPhase avatar's structural identity.
     */
    private List<TeamAvatarProposal> computeTeamNumber(
            List<TeamAvatar> fromAvatars,
            int groupCount,
            Map<UUID, Team> teamById,
            String sortType) {
        // fromAvatars is already ordered by group_number, group_position (repository contract)
        List<TeamAvatarProposal> proposals = new ArrayList<>(fromAvatars.size());
        for (int i = 0; i < fromAvatars.size(); i++) {
            TeamAvatar av = fromAvatars.get(i);
            Team team = requireTeamForDisplay(av, teamById);
            int targetGroup = (i % groupCount) + 1;
            int targetPosition = (i / groupCount) + 1;
            // E51S13 (AC-IMPL-DTO-SORTTYPE-NULLABLE): populate sortType from toSection
            proposals.add(
                    new TeamAvatarProposal(
                            av.getTeamId(),
                            team.getTeamNumber(),
                            team.getDescription(),
                            targetGroup,
                            targetPosition,
                            av.getGroupNumber(),
                            av.getGroupPosition(),
                            sortType));
        }
        return proposals;
    }

    /**
     * Placement-group distribution: teams keep their Phase-N group; positions within each group are
     * re-assigned by descending points (higher points = rank 1 = position 1).
     *
     * <p>Teams with no rating are placed at the end (effectively rank last).
     *
     * <p>E48S20: source fields populated from fromPhase avatar structural identity.
     */
    private List<TeamAvatarProposal> computePlacementGroup(
            List<TeamAvatar> fromAvatars, Map<UUID, Team> teamById, String sortType) {
        // Group avatars by their fromPhase groupNumber, preserving encounter order within each
        // group
        Map<Integer, List<TeamAvatar>> byGroup = new LinkedHashMap<>();
        for (TeamAvatar av : fromAvatars) {
            byGroup.computeIfAbsent(av.getGroupNumber(), k -> new ArrayList<>()).add(av);
        }

        List<TeamAvatarProposal> proposals = new ArrayList<>(fromAvatars.size());
        for (Map.Entry<Integer, List<TeamAvatar>> entry : byGroup.entrySet()) {
            int groupNumber = entry.getKey();
            List<TeamAvatar> groupAvatars = entry.getValue();

            // Sort by descending points — higher points = better placement = lower position index
            groupAvatars.sort(
                    Comparator.comparingInt(
                                    (TeamAvatar av) ->
                                            teamAvatarRatingRepository
                                                    .findByAvatarId(av.getId())
                                                    .map(r -> r.getPoints())
                                                    .orElse(Integer.MIN_VALUE))
                            .reversed());

            for (int pos = 0; pos < groupAvatars.size(); pos++) {
                TeamAvatar av = groupAvatars.get(pos);
                Team team = requireTeamForDisplay(av, teamById);
                // E51S13 (AC-IMPL-DTO-SORTTYPE-NULLABLE): populate sortType from toSection
                proposals.add(
                        new TeamAvatarProposal(
                                av.getTeamId(),
                                team.getTeamNumber(),
                                team.getDescription(),
                                groupNumber,
                                pos + 1,
                                av.getGroupNumber(),
                                av.getGroupPosition(),
                                sortType));
            }
        }
        return proposals;
    }

    /**
     * Group-placement distribution: rank-N finisher from every Phase-N group → target group N.
     *
     * <p>Truncates to the minimum group size when groups are unequal — teams with rank beyond the
     * minimum are excluded (no equivalent rank slot in the smaller group).
     *
     * <p>Within each target group, positions are assigned in the order the source groups are
     * encountered (source group 1 first, source group 2 second, etc.).
     *
     * <p>E48S20: source fields populated from fromPhase avatar structural identity.
     */
    private List<TeamAvatarProposal> computeGroupPlacement(
            List<TeamAvatar> fromAvatars, Map<UUID, Team> teamById, String sortType) {
        // Group avatars by their fromPhase groupNumber
        Map<Integer, List<TeamAvatar>> byGroup = new LinkedHashMap<>();
        for (TeamAvatar av : fromAvatars) {
            byGroup.computeIfAbsent(av.getGroupNumber(), k -> new ArrayList<>()).add(av);
        }

        // Sort each group by descending points: index 0 = rank 1 (best), index 1 = rank 2, ...
        for (List<TeamAvatar> groupAvatars : byGroup.values()) {
            groupAvatars.sort(
                    Comparator.comparingInt(
                                    (TeamAvatar av) ->
                                            teamAvatarRatingRepository
                                                    .findByAvatarId(av.getId())
                                                    .map(r -> r.getPoints())
                                                    .orElse(Integer.MIN_VALUE))
                            .reversed());
        }

        // Truncate to minimum group size
        int minSize = byGroup.values().stream().mapToInt(List::size).min().orElse(0);

        // Build proposals: rank slot r → target group (r+1), position = source-group order
        List<TeamAvatarProposal> proposals = new ArrayList<>();
        for (int rankSlot = 0; rankSlot < minSize; rankSlot++) {
            int targetGroup = rankSlot + 1;
            int posWithinGroup = 1;
            for (List<TeamAvatar> sourceGroup : byGroup.values()) {
                TeamAvatar av = sourceGroup.get(rankSlot);
                Team team = requireTeamForDisplay(av, teamById);
                // E51S13 (AC-IMPL-DTO-SORTTYPE-NULLABLE): populate sortType from toSection
                proposals.add(
                        new TeamAvatarProposal(
                                av.getTeamId(),
                                team.getTeamNumber(),
                                team.getDescription(),
                                targetGroup,
                                posWithinGroup,
                                av.getGroupNumber(),
                                av.getGroupPosition(),
                                sortType));
                posWithinGroup++;
            }
        }
        return proposals;
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private void validateAssignments(List<TeamAvatarProposal> assignments, DraftSection toSection) {
        if (assignments == null || assignments.isEmpty()) {
            throw new IllegalArgumentException(
                    "assignments must not be null or empty (AC-ERROR-HANDLING-INVALID-ASSIGNMENT)");
        }

        // Check for duplicate (teamId) entries
        long distinctTeamIds =
                assignments.stream().map(TeamAvatarProposal::teamId).distinct().count();
        if (distinctTeamIds < assignments.size()) {
            throw new IllegalArgumentException(
                    "assignments contains duplicate teamIds"
                            + " (AC-ERROR-HANDLING-INVALID-ASSIGNMENT)");
        }

        // Check for duplicate structural identity (groupNumber, groupPosition)
        long distinctSlots =
                assignments.stream()
                        .map(p -> p.groupNumber() + ":" + p.groupPosition())
                        .distinct()
                        .count();
        if (distinctSlots < assignments.size()) {
            throw new IllegalArgumentException(
                    "assignments contains duplicate (groupNumber, groupPosition) slots"
                            + " (AC-ERROR-HANDLING-INVALID-ASSIGNMENT)");
        }

        // Check group/position ranges
        int groupCount = toSection.getGroupCount();
        for (TeamAvatarProposal p : assignments) {
            if (p.groupNumber() < 1 || p.groupNumber() > groupCount) {
                throw new IllegalArgumentException(
                        "groupNumber "
                                + p.groupNumber()
                                + " out of range [1, "
                                + groupCount
                                + "] (AC-ERROR-HANDLING-INVALID-ASSIGNMENT)");
            }
            if (p.groupPosition() < 1) {
                throw new IllegalArgumentException(
                        "groupPosition must be ≥ 1, got: "
                                + p.groupPosition()
                                + " (AC-ERROR-HANDLING-INVALID-ASSIGNMENT)");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Display-field population helpers (E48S20)
    // -------------------------------------------------------------------------

    /**
     * Builds a {@code teamId → Team} lookup map from the from-phase avatar list.
     *
     * <p>Loads each distinct teamId in the avatar list via {@link TeamRepository#findById(UUID)}
     * exactly once (no N+1). Used by Phase-2+ branches to populate display fields without
     * per-avatar repository round-trips.
     *
     * @param fromAvatars avatars whose teamId set is used as the lookup key set
     * @return map of teamId → Team; keyed by distinct teamIds in fromAvatars
     */
    private Map<UUID, Team> buildTeamLookup(List<TeamAvatar> fromAvatars) {
        Map<UUID, Team> teamById = new HashMap<>(fromAvatars.size() * 2);
        for (TeamAvatar av : fromAvatars) {
            UUID teamId = av.getTeamId();
            if (!teamById.containsKey(teamId)) {
                Team team =
                        teamRepository
                                .findById(teamId)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Team not found for teamId="
                                                                + teamId
                                                                + " (AC-ERROR-MISSING-TEAM-DEFENSE)"));
                validateTeamDisplayFields(team);
                teamById.put(teamId, team);
            }
        }
        return teamById;
    }

    /**
     * Returns the {@link Team} for the given avatar's teamId from the pre-built lookup map.
     *
     * <p>Throws {@link IllegalStateException} if the team is not in the map (should not happen if
     * {@link #buildTeamLookup} was called with the same avatar list).
     *
     * @param av the avatar whose teamId is looked up
     * @param teamById pre-built lookup map
     * @return the Team entity; never null
     * @throws IllegalStateException if not found in the map
     */
    private Team requireTeamForDisplay(TeamAvatar av, Map<UUID, Team> teamById) {
        Team team = teamById.get(av.getTeamId());
        if (team == null) {
            throw new IllegalStateException(
                    "Team not found in lookup for teamId="
                            + av.getTeamId()
                            + " (AC-ERROR-MISSING-TEAM-DEFENSE)");
        }
        return team;
    }

    /**
     * Validates that a Team entity has non-null {@code teamNumber} (implicit, since it is {@code
     * int}) and non-null {@code description} for organizer-facing display.
     *
     * <p>Phase-1 branch calls this directly on Team entities. Phase-2+ branch calls it inside
     * {@link #buildTeamLookup}.
     *
     * @param team the Team to validate
     * @throws IllegalStateException if {@code description} is null (AC-ERROR-MISSING-TEAM-DEFENSE)
     */
    private void validateTeamDisplayFields(Team team) {
        // teamNumber is int — cannot be null by construction
        if (team.getDescription() == null) {
            throw new IllegalStateException(
                    "Team.description is null for teamId="
                            + team.getId()
                            + " — corrupt data; cannot produce organizer-facing proposal"
                            + " (AC-ERROR-MISSING-TEAM-DEFENSE)");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Phase requirePhase(UUID phaseId) {
        return phaseRepository
                .findById(phaseId)
                .orElseThrow(() -> new IllegalArgumentException("Phase not found: " + phaseId));
    }

    private Tournament requireTournament(UUID tournamentId) {
        return tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Tournament not found: " + tournamentId));
    }

    /**
     * Returns the fromPhase for the given toPhase, or {@code Optional.empty()} if toPhase is the
     * first phase (sequenceNumber ≤ 1).
     *
     * <p>Caller branches on the return value: Empty → Phase-1-Branch (E48S18); Present → Phase-N+1
     * path (E48S07).
     *
     * @param toPhase the target phase
     * @return Optional.empty() for Phase 1; Optional.of(fromPhase) for Phase 2+
     * @throws IllegalStateException if Phase 2+ fromPhase is not found
     */
    private Optional<Phase> requireFromPhase(Phase toPhase) {
        if (toPhase.getSequenceNumber() <= 1) {
            // Phase-1-Branch (E48S18): no fromPhase needed; caller uses tournament.Teams
            return Optional.empty();
        }
        return Optional.of(
                phaseRepository
                        .findByTournamentIdAndSequenceNumber(
                                toPhase.getTournamentId(), toPhase.getSequenceNumber() - 1)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "fromPhase not found for tournamentId="
                                                        + toPhase.getTournamentId()
                                                        + ", sequenceNumber="
                                                        + (toPhase.getSequenceNumber() - 1))));
    }

    /**
     * Parses the tournament's {@code draft_json} and returns the {@link DraftSection} for the given
     * {@code sequenceNumber}.
     *
     * @throws IllegalArgumentException if {@code draft_json} is null, blank, or unparseable (→ HTTP
     *     400 per AC-ERROR-HANDLING-DRAFT-JSON-NULL)
     * @throws IllegalArgumentException if no section with the given sequenceNumber exists
     */
    private DraftSection resolveDraftSection(Tournament tournament, int sequenceNumber) {
        String json = tournament.getDraftJson();
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                    "draft_json is null or blank for tournamentId="
                            + tournament.getId()
                            + " (AC-ERROR-HANDLING-DRAFT-JSON-NULL)");
        }

        DraftConfig draftConfig;
        try {
            draftConfig = objectMapper.readValue(json, DraftConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse draft_json for tournamentId="
                            + tournament.getId()
                            + ": "
                            + e.getMessage()
                            + " (AC-ERROR-HANDLING-DRAFT-JSON-NULL)",
                    e);
        }

        return draftConfig.getSections().stream()
                .filter(s -> s.getSectionNumber() == sequenceNumber)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No DraftSection found for sectionNumber="
                                                + sequenceNumber
                                                + " in tournamentId="
                                                + tournament.getId()));
    }
}
