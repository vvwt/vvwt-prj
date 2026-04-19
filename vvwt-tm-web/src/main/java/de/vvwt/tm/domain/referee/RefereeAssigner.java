package de.vvwt.tm.domain.referee;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring service that assigns referee teams to every eligible match in a phase.
 *
 * <h2>Core algorithm (AC4)</h2>
 *
 * <p>For every lap in the phase the service:
 *
 * <ol>
 *   <li>Determines which teams are playing in that lap.
 *   <li>Builds a candidate set: teams with {@code referee_assignment = TRUE} that are NOT playing
 *       in this lap.
 *   <li>Applies preference ordering from {@code Match.refereePreferenceConfig}.
 *   <li>Applies round-robin balancing: candidates with fewer prior assignments go first.
 *   <li>Assigns the first candidate to the match, or records a warning if the set is empty.
 * </ol>
 *
 * <h2>Manual overrides (AC3)</h2>
 *
 * <p>Any match with a non-null {@code refereeDescription} is left unchanged and counted as
 * "manually overridden" in the final report.
 *
 * <h2>Transactionality (AC18)</h2>
 *
 * <p>The entire method runs inside a single {@link Transactional} boundary. A mid-run failure rolls
 * back all partial assignments.
 *
 * <h2>Tenant safety (AC17)</h2>
 *
 * <p>All repository calls go through the tenant-scoped repositories from E03S05. Cross-tenant
 * access is structurally impossible.
 *
 * @see RefereeAssignmentReport
 * @see RefereePreferenceConfig
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S10.story.md">Story
 *     E03S10</a>
 */
@Service
public class RefereeAssigner {

    private static final Logger LOG = LoggerFactory.getLogger(RefereeAssigner.class);

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamRepository teamRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final ObjectMapper objectMapper;

    public RefereeAssigner(
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamRepository teamRepository,
            TeamAvatarRepository teamAvatarRepository,
            ObjectMapper objectMapper) {
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamRepository = teamRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Assigns referee teams to every eligible match in the given phase.
     *
     * <p>The method is idempotent on matches that already have a non-null {@code
     * refereeDescription} — those are counted as "manually overridden" and left untouched. Matches
     * that already have a {@code refereeTeamId} but no {@code refereeDescription} are treated as
     * auto-assign targets and may be overwritten.
     *
     * @param phaseId the phase to process; must not be {@code null}
     * @return a summary report of the assignment run; never {@code null}
     * @throws IllegalArgumentException if the phase does not exist
     * @throws IllegalStateException if any match in the phase has null {@code lapNumber} or {@code
     *     fieldNumber} (slot optimization not yet run)
     */
    @Transactional
    public RefereeAssignmentReport assignReferees(UUID phaseId) {
        if (phaseId == null) {
            throw new NullPointerException("phaseId must not be null");
        }

        // -----------------------------------------------------------------------
        // Step 1: Load and validate phase (AC12)
        // -----------------------------------------------------------------------
        Phase phase =
                phaseRepository
                        .findById(phaseId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Phase not found: "
                                                        + phaseId
                                                        + ". No assignment performed."));

        // -----------------------------------------------------------------------
        // Step 2: Load matches
        // -----------------------------------------------------------------------
        List<Match> allMatches = matchRepository.findByPhaseId(phaseId);

        if (allMatches.isEmpty()) {
            LOG.info("RefereeAssigner: phase {} has no matches — nothing to assign.", phaseId);
            return RefereeAssignmentReport.builder().totalMatches(0).build();
        }

        // -----------------------------------------------------------------------
        // Step 3: Slot-coordinate precondition check (AC2, AC13)
        // -----------------------------------------------------------------------
        for (Match match : allMatches) {
            if (match.getLapNumber() == null || match.getFieldNumber() == null) {
                throw new IllegalStateException(
                        "Phase "
                                + phaseId
                                + " has matches without slot coordinates "
                                + "(match "
                                + match.getId()
                                + "). Run slot optimization (D-29 step 2) before calling"
                                + " assignReferees.");
            }
        }

        // -----------------------------------------------------------------------
        // Step 4: Load avatars and build avatarId → teamId index
        // -----------------------------------------------------------------------
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        Map<UUID, UUID> avatarIdToTeamId = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar avatar : avatars) {
            avatarIdToTeamId.put(avatar.getId(), avatar.getTeamId());
        }

        // -----------------------------------------------------------------------
        // Step 5: Load teams and build lookup structures
        // -----------------------------------------------------------------------
        List<Team> teams = teamRepository.findByTournamentId(phase.getTournamentId());
        Map<UUID, Team> teamById = new HashMap<>(teams.size() * 2);
        Set<UUID> eligibleRefereeTeamIds = new HashSet<>();
        for (Team team : teams) {
            teamById.put(team.getId(), team);
            if (team.isRefereeAssignment()) {
                eligibleRefereeTeamIds.add(team.getId());
            }
        }

        // -----------------------------------------------------------------------
        // Step 6: Partition matches by lap (sorted ascending for determinism)
        // -----------------------------------------------------------------------
        Map<Integer, List<Match>> matchesByLap = new TreeMap<>();
        for (Match match : allMatches) {
            matchesByLap.computeIfAbsent(match.getLapNumber(), k -> new ArrayList<>()).add(match);
        }

        // -----------------------------------------------------------------------
        // Step 7: Per-lap assignment loop (AC4, AC5, AC6)
        // -----------------------------------------------------------------------
        RefereeAssignmentReport.Builder reportBuilder =
                RefereeAssignmentReport.builder().totalMatches(allMatches.size());

        for (Map.Entry<Integer, List<Match>> lapEntry : matchesByLap.entrySet()) {
            int lapNumber = lapEntry.getKey();
            List<Match> lapMatches = lapEntry.getValue();

            // 7a: Determine which teams are playing in this lap
            Set<UUID> playingTeamIds = new HashSet<>();
            for (Match match : lapMatches) {
                UUID team1Id = avatarIdToTeamId.get(match.getMemberAvatar1Id());
                UUID team2Id = avatarIdToTeamId.get(match.getMemberAvatar2Id());
                if (team1Id != null) playingTeamIds.add(team1Id);
                if (team2Id != null) playingTeamIds.add(team2Id);
            }

            // 7b: Separate matches into to-assign and skipped (manual overrides, AC3)
            List<Match> toAssign = new ArrayList<>();
            int lapOverrideCount = 0;
            for (Match match : lapMatches) {
                if (match.getRefereeDescription() != null) {
                    lapOverrideCount++;
                    LOG.debug(
                            "RefereeAssigner: lap={} match={} — manual override"
                                    + " (refereeDescription='{}'), skipping.",
                            lapNumber,
                            match.getId(),
                            match.getRefereeDescription());
                } else {
                    toAssign.add(match);
                }
            }
            reportBuilder.addOverridden(lapOverrideCount);

            // Sort toAssign by match ID for deterministic ordering within a lap
            toAssign.sort(Comparator.comparing(Match::getId));

            // Track teams already assigned as referee in this lap
            // (a team can only physically referee one match at a time — AC8)
            Set<UUID> alreadyRefereesThisLap = new HashSet<>();

            // 7c: For each match to assign
            for (Match match : toAssign) {

                // 7c-i: Parse preference config (AC5)
                RefereePreferenceConfig prefs =
                        RefereePreferenceConfig.parse(
                                match.getRefereePreferenceConfig(), objectMapper);

                // 7c-ii: Build candidate set: eligible AND not playing AND not already refereeing
                //        in this lap (physical constraint: one team = one court at a time)
                Set<UUID> candidateSet = new HashSet<>(eligibleRefereeTeamIds);
                candidateSet.removeAll(playingTeamIds);
                candidateSet.removeAll(alreadyRefereesThisLap);

                // 7c-iii & 7c-iv: Build ordered candidate list applying preferences + balancing
                List<UUID> candidateList = buildCandidateList(candidateSet, prefs, reportBuilder);

                // 7c-v: No candidates available
                if (candidateList.isEmpty()) {
                    String warning =
                            "Lap "
                                    + lapNumber
                                    + ": no eligible referee available for match "
                                    + match.getId()
                                    + ".";
                    LOG.warn("RefereeAssigner: {}", warning);
                    reportBuilder.addWarning(warning).incrementNoReferee();
                    LOG.debug(
                            "RefereeAssigner: lap={} match={} — no eligible referee.",
                            lapNumber,
                            match.getId());
                    continue;
                }

                // 7c-vi: Assign the first candidate
                UUID chosenTeamId = candidateList.get(0);
                match.setRefereeTeamId(chosenTeamId);
                matchRepository.save(match);
                reportBuilder.incrementAssigned().recordAssignment(chosenTeamId);
                alreadyRefereesThisLap.add(chosenTeamId); // one court at a time (AC8)

                LOG.debug(
                        "RefereeAssigner: lap={} field={} match={} — assigned refereeTeamId={}.",
                        lapNumber,
                        match.getFieldNumber(),
                        match.getId(),
                        chosenTeamId);
            }
        }

        // -----------------------------------------------------------------------
        // Step 8–10: Finalise and log summary (AC15)
        // -----------------------------------------------------------------------
        RefereeAssignmentReport report = reportBuilder.build();

        LOG.info(
                "RefereeAssigner: phase={} summary — total={}, assigned={}, overridden={},"
                        + " noReferee={}, warnings={}",
                phaseId,
                report.getTotalMatches(),
                report.getAssignedCount(),
                report.getOverriddenCount(),
                report.getNoRefereeCount(),
                report.getWarnings().size());

        if (!report.getWarnings().isEmpty()) {
            LOG.warn(
                    "RefereeAssigner: {} match(es) could not be assigned a referee and require"
                            + " manual intervention.",
                    report.getNoRefereeCount());
        }

        LOG.info(
                "RefereeAssigner: per-team assignment counts — {}",
                report.getPerTeamAssignmentCount());

        return report;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an ordered candidate list from the given candidate set, applying preference ordering
     * and round-robin balancing.
     *
     * <p>Ordering priority (highest to lowest):
     *
     * <ol>
     *   <li>Preferred teams (in preference order), if they are in the candidate set.
     *   <li>Remaining candidates, sorted by current assignment count ascending (least assigned
     *       first), then by team ID for deterministic tie-breaking.
     * </ol>
     *
     * @param candidateSet teams eligible to referee this match (eligible AND not playing)
     * @param prefs preference configuration for the match
     * @param reportBuilder current report builder to read per-team assignment counts
     * @return ordered candidate list; may be empty if candidateSet is empty
     */
    private List<UUID> buildCandidateList(
            Set<UUID> candidateSet,
            RefereePreferenceConfig prefs,
            RefereeAssignmentReport.Builder reportBuilder) {
        if (candidateSet.isEmpty()) {
            return List.of();
        }

        List<UUID> result = new ArrayList<>(candidateSet.size());
        Set<UUID> remaining = new HashSet<>(candidateSet);

        // 1. Add preferred teams first (in preference order), if they are in the candidate set
        for (UUID preferredId : prefs.getPreferred()) {
            if (remaining.remove(preferredId)) {
                result.add(preferredId);
            }
        }

        // 2. Add remaining candidates sorted by assignment count ASC, then by UUID for stability
        List<UUID> remainingList = new ArrayList<>(remaining);
        remainingList.sort(
                Comparator.comparingInt((UUID teamId) -> reportBuilder.getAssignmentCount(teamId))
                        .thenComparing(Comparator.naturalOrder()));
        result.addAll(remainingList);

        return result;
    }
}
