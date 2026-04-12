package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Domain service for live monitoring endpoints (E05S10).
 *
 * <h2>Responsibilities (AC1–AC4)</h2>
 * <ul>
 *   <li>{@link #getGroupTable(UUID, int)} — returns the ranked standings for a single group
 *       within the phase (AC1). Computed on read from {@link TeamAvatarRating} rows (D-24).</li>
 *   <li>{@link #getAllGroupTables(UUID)} — returns all groups' standings for the phase (AC2).
 *       Delegates to {@link #getGroupTable} per group.</li>
 *   <li>{@link #getLapMatches(UUID, int)} — returns all matches (with set scores) for a given
 *       lap in the phase (AC3).</li>
 *   <li>{@link #getCurrentLapSummary(UUID)} — returns the current lap number and match counts
 *       by state for that lap (AC4). Enables the SPA to show lap progress.</li>
 * </ul>
 *
 * <h2>Group table computation (D-24)</h2>
 * <p>There is no materialized {@code group_table} table. Group tables are derived at read time
 * by:
 * <ol>
 *   <li>Loading all {@link TeamAvatar} rows for the phase.</li>
 *   <li>Loading all {@link TeamAvatarRating} rows for the phase (1:1 with avatar).</li>
 *   <li>Joining ratings to avatars in memory via {@code avatarId}.</li>
 *   <li>Partitioning by {@code groupNumber}.</li>
 *   <li>Sorting each group's entries using {@link TeamAvatarRating#compareTo} (D-33 sort order).</li>
 * </ol>
 *
 * <h2>Tenant scope (DEC-5, DEC-17, AC14)</h2>
 * <p>All repository calls are tenant-scoped. The phase is verified to belong to the active
 * tenant via the tournament ownership check in {@link #requirePhaseWithTenantScope(UUID)}.
 * 404 is returned for any phase not owned by the active tenant (info-leakage prevention).
 *
 * @see TeamAvatarRating#compareTo(TeamAvatarRating)
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S10.story.md">Story E05S10</a>
 */
@Service
@Transactional(readOnly = true)
public class LiveMonitoringService {

    private final PhaseRepository phaseRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamAvatarRatingRepository ratingRepository;
    private final MatchRepository matchRepository;
    private final SetResultRepository setResultRepository;
    private final TeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;

    public LiveMonitoringService(PhaseRepository phaseRepository,
                                 TeamAvatarRepository teamAvatarRepository,
                                 TeamAvatarRatingRepository ratingRepository,
                                 MatchRepository matchRepository,
                                 SetResultRepository setResultRepository,
                                 TeamRepository teamRepository,
                                 TournamentRepository tournamentRepository) {
        this.phaseRepository = phaseRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.ratingRepository = ratingRepository;
        this.matchRepository = matchRepository;
        this.setResultRepository = setResultRepository;
        this.teamRepository = teamRepository;
        this.tournamentRepository = tournamentRepository;
    }

    // =========================================================================
    // AC1: getGroupTable
    // =========================================================================

    /**
     * Returns the ranked standings for a single group within the given phase (AC1).
     *
     * <p>Computed on read from {@link TeamAvatarRating} rows — no materialized group table
     * exists (D-24). Sort order follows {@link TeamAvatarRating#compareTo} (D-33):
     * points DESC → setQuotient DESC → ballQuotient DESC, without-assessment rows last (D-26).
     *
     * @param phaseId     the phase to query
     * @param groupNumber the group number (1-indexed) to return
     * @return list of group table entries sorted by ranking (best first); empty if no avatars
     * @throws NoSuchElementException if the phase does not exist or belongs to a different tenant
     * @throws IllegalArgumentException if groupNumber is less than 1
     */
    public List<GroupTableEntry> getGroupTable(UUID phaseId, int groupNumber) {
        if (groupNumber < 1) {
            throw new IllegalArgumentException("groupNumber must be >= 1, got: " + groupNumber);
        }
        requirePhaseWithTenantScope(phaseId);
        return buildGroupTable(phaseId, groupNumber);
    }

    /**
     * Returns all groups' ranked standings for the given phase (AC2).
     *
     * <p>Groups are ordered by group number (ascending). Each group's entries are sorted
     * by the D-33 ranking order.
     *
     * @param phaseId the phase to query
     * @return map from groupNumber to sorted list of entries; never {@code null}
     * @throws NoSuchElementException if the phase does not exist or belongs to a different tenant
     */
    public Map<Integer, List<GroupTableEntry>> getAllGroupTables(UUID phaseId) {
        requirePhaseWithTenantScope(phaseId);

        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        if (avatars.isEmpty()) {
            return Collections.emptyMap();
        }

        // Collect distinct group numbers
        List<Integer> groupNumbers = avatars.stream()
                .map(TeamAvatar::getGroupNumber)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // Build ratings lookup once for all groups
        List<TeamAvatarRating> allRatings = ratingRepository.findByPhaseId(phaseId);
        Map<UUID, TeamAvatarRating> ratingByAvatarId = new HashMap<>();
        for (TeamAvatarRating r : allRatings) {
            ratingByAvatarId.put(r.getAvatarId(), r);
        }

        // Build team description lookup once for all groups
        Map<UUID, String> teamDescriptionById = loadTeamDescriptions(phaseId, avatars);

        Map<Integer, List<GroupTableEntry>> result = new HashMap<>();
        for (int gn : groupNumbers) {
            List<GroupTableEntry> entries = buildGroupTableFromLoaded(avatars, ratingByAvatarId,
                    teamDescriptionById, gn);
            result.put(gn, entries);
        }
        return result;
    }

    // =========================================================================
    // AC3: getLapMatches
    // =========================================================================

    /**
     * Returns all matches for the given lap in the given phase, with set scores (AC3).
     *
     * <p>Matches are ordered by {@code fieldNumber}. For each match, set results are included
     * ordered by {@code setIndex} (0-based).
     *
     * @param phaseId   the phase to query
     * @param lapNumber the lap number (1-indexed) to return
     * @return list of lap match details ordered by fieldNumber; empty if lap has no matches
     * @throws NoSuchElementException   if the phase does not exist or belongs to a different tenant
     * @throws IllegalArgumentException if lapNumber is less than 1
     */
    public List<LapMatchDetail> getLapMatches(UUID phaseId, int lapNumber) {
        if (lapNumber < 1) {
            throw new IllegalArgumentException("lapNumber must be >= 1, got: " + lapNumber);
        }
        requirePhaseWithTenantScope(phaseId);

        List<Match> matches = matchRepository.findByPhaseIdAndLapNumber(phaseId, lapNumber);
        if (matches.isEmpty()) {
            return Collections.emptyList();
        }

        // Build avatar description lookup
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        Map<UUID, TeamAvatar> avatarById = new HashMap<>();
        for (TeamAvatar a : avatars) {
            avatarById.put(a.getId(), a);
        }

        // Build team description lookup for referee resolution
        Map<UUID, String> teamDescriptionById = loadTeamDescriptions(phaseId, avatars);

        return matches.stream()
                .sorted(Comparator.comparingInt(m -> m.getFieldNumber() != null ? m.getFieldNumber() : 0))
                .map(match -> {
                    TeamAvatar avatar1 = avatarById.get(match.getMemberAvatar1Id());
                    TeamAvatar avatar2 = avatarById.get(match.getMemberAvatar2Id());
                    String team1Desc = resolveAvatarDescription(avatar1, teamDescriptionById);
                    String team2Desc = resolveAvatarDescription(avatar2, teamDescriptionById);
                    String refereeDesc = resolveRefereeDescription(match, teamDescriptionById);

                    List<SetResult> setResults = setResultRepository.findByMatchId(match.getId());
                    List<LapMatchSetResult> sets = setResults.stream()
                            .sorted(Comparator.comparingInt(SetResult::getSetIndex))
                            .map(sr -> new LapMatchSetResult(sr.getSetIndex(),
                                    sr.getTeam1Points(), sr.getTeam2Points(),
                                    sr.getSetState().name()))
                            .collect(Collectors.toList());

                    return new LapMatchDetail(
                            match.getId(),
                            match.getFieldNumber(),
                            match.getMemberAvatar1Id(),
                            match.getMemberAvatar2Id(),
                            team1Desc,
                            team2Desc,
                            refereeDesc,
                            match.getMatchState().name(),
                            sets
                    );
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // AC4: getCurrentLapSummary
    // =========================================================================

    /**
     * Returns the current lap summary for the given phase (AC4).
     *
     * <p>The summary includes:
     * <ul>
     *   <li>{@code currentLapNumber} — from {@code phase.currentLapNumber}</li>
     *   <li>{@code totalLapCount} — number of distinct non-null lap numbers across all phase matches</li>
     *   <li>{@code matchCountByState} — counts of matches in the current lap by {@link MatchState}</li>
     *   <li>{@code phaseStatus} — the phase status name (PENDING/ACTIVE/COMPLETED)</li>
     * </ul>
     *
     * @param phaseId the phase to summarize
     * @return the current lap summary; never {@code null}
     * @throws NoSuchElementException if the phase does not exist or belongs to a different tenant
     */
    public CurrentLapSummary getCurrentLapSummary(UUID phaseId) {
        Phase phase = requirePhaseWithTenantScope(phaseId);

        int currentLapNumber = phase.getCurrentLapNumber();

        // Total lap count — distinct non-null lap numbers across all matches
        List<Match> allMatches = matchRepository.findByPhaseId(phaseId);
        int totalLapCount = (int) allMatches.stream()
                .map(Match::getLapNumber)
                .filter(lap -> lap != null)
                .distinct()
                .count();

        // Match counts by state for the current lap
        List<Match> lapMatches;
        if (currentLapNumber > 0) {
            lapMatches = allMatches.stream()
                    .filter(m -> m.getLapNumber() != null && m.getLapNumber() == currentLapNumber)
                    .collect(Collectors.toList());
        } else {
            lapMatches = Collections.emptyList();
        }

        Map<String, Long> matchCountByState = new HashMap<>();
        for (Match m : lapMatches) {
            String stateName = m.getMatchState().name();
            matchCountByState.merge(stateName, 1L, Long::sum);
        }

        return new CurrentLapSummary(
                currentLapNumber,
                totalLapCount,
                matchCountByState,
                phase.getStatus()
        );
    }

    // =========================================================================
    // Value types
    // =========================================================================

    /**
     * One ranked entry in a group table (AC1, AC2).
     *
     * @param rank              1-based rank within the group (D-33 sort position)
     * @param avatarId          avatar identifier
     * @param teamDescription   human-readable team label (from {@code TeamAvatar.description}
     *                          or team name)
     * @param groupNumber       group number within the phase
     * @param groupPosition     initial seed position within the group
     * @param matchCount        matches played
     * @param points            accumulated points
     * @param setsWon           sets won
     * @param setsLost          sets lost
     * @param setQuotient       {@code setsWon/setsLost} ({@code Double.MAX_VALUE} sentinel when no losses)
     * @param ballsWon          individual balls won
     * @param ballsLost         individual balls lost
     * @param ballQuotient      {@code ballsWon/ballsLost} ({@code Double.MAX_VALUE} sentinel when no losses)
     * @param isWithoutAssessment {@code true} if this avatar is excluded from normal standings (D-26)
     */
    public record GroupTableEntry(
            int rank,
            UUID avatarId,
            String teamDescription,
            int groupNumber,
            int groupPosition,
            int matchCount,
            int points,
            int setsWon,
            int setsLost,
            double setQuotient,
            int ballsWon,
            int ballsLost,
            double ballQuotient,
            boolean isWithoutAssessment
    ) {}

    /**
     * One match in a lap (AC3).
     *
     * @param matchId          match identifier
     * @param fieldNumber      court number (may be null if unscheduled)
     * @param avatar1Id        UUID of the avatar for team 1
     * @param avatar2Id        UUID of the avatar for team 2
     * @param team1Description human-readable label for team 1
     * @param team2Description human-readable label for team 2
     * @param refereeDescription human-readable referee label
     * @param matchState       match state name (e.g., "ENABLED", "INPROGRESS", "FINISHED_WINNER1")
     * @param setResults       ordered list of set results (may be empty before scoring begins)
     */
    public record LapMatchDetail(
            UUID matchId,
            Integer fieldNumber,
            UUID avatar1Id,
            UUID avatar2Id,
            String team1Description,
            String team2Description,
            String refereeDescription,
            String matchState,
            List<LapMatchSetResult> setResults
    ) {}

    /**
     * One set result within a lap match (AC3).
     *
     * @param setIndex    0-based set index
     * @param team1Points balls scored by team 1
     * @param team2Points balls scored by team 2
     * @param setState    set state name (e.g., "WINNER1", "STANDOFF")
     */
    public record LapMatchSetResult(
            int setIndex,
            int team1Points,
            int team2Points,
            String setState
    ) {}

    /**
     * Current lap summary for the live monitoring header (AC4).
     *
     * @param currentLapNumber   current lap (from {@code phase.currentLapNumber})
     * @param totalLapCount      total number of laps in this phase (distinct non-null lap numbers)
     * @param matchCountByState  counts of matches in the current lap, keyed by state name
     * @param phaseStatus        phase status name (PENDING / ACTIVE / COMPLETED)
     */
    public record CurrentLapSummary(
            int currentLapNumber,
            int totalLapCount,
            Map<String, Long> matchCountByState,
            String phaseStatus
    ) {}

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Loads the phase, verifying it belongs to the active tenant via tournament ownership check.
     * Returns the phase on success; throws {@link NoSuchElementException} for 404 on failure.
     */
    private Phase requirePhaseWithTenantScope(UUID phaseId) {
        Phase phase = phaseRepository.findById(phaseId)
                .orElseThrow(() -> new NoSuchElementException("Phase not found: " + phaseId));
        // Tenant check: if the phase's tournament is not visible to the active tenant, throw 404
        tournamentRepository.findById(phase.getTournamentId())
                .orElseThrow(() -> new NoSuchElementException("Phase not found: " + phaseId));
        return phase;
    }

    /**
     * Builds group table entries for a single group, loading avatars and ratings fresh.
     * Used by {@link #getGroupTable(UUID, int)} (single-group path).
     */
    private List<GroupTableEntry> buildGroupTable(UUID phaseId, int groupNumber) {
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);

        List<TeamAvatarRating> allRatings = ratingRepository.findByPhaseId(phaseId);
        Map<UUID, TeamAvatarRating> ratingByAvatarId = new HashMap<>();
        for (TeamAvatarRating r : allRatings) {
            ratingByAvatarId.put(r.getAvatarId(), r);
        }

        Map<UUID, String> teamDescriptionById = loadTeamDescriptions(phaseId, avatars);

        return buildGroupTableFromLoaded(avatars, ratingByAvatarId, teamDescriptionById, groupNumber);
    }

    /**
     * Builds group table entries for {@code groupNumber} from pre-loaded data structures.
     * Sorting uses {@link TeamAvatarRating#compareTo} (D-33 canonical order).
     */
    private List<GroupTableEntry> buildGroupTableFromLoaded(
            List<TeamAvatar> allAvatars,
            Map<UUID, TeamAvatarRating> ratingByAvatarId,
            Map<UUID, String> teamDescriptionById,
            int groupNumber) {

        // Collect avatars for this group
        List<TeamAvatar> groupAvatars = allAvatars.stream()
                .filter(a -> a.getGroupNumber() == groupNumber)
                .collect(Collectors.toList());

        if (groupAvatars.isEmpty()) {
            return Collections.emptyList();
        }

        // Pair each avatar with its rating (or a zero-rating if missing)
        record AvatarWithRating(TeamAvatar avatar, TeamAvatarRating rating) {}
        List<AvatarWithRating> pairs = new ArrayList<>();
        for (TeamAvatar avatar : groupAvatars) {
            TeamAvatarRating rating = ratingByAvatarId.get(avatar.getId());
            if (rating == null) {
                // Avatar exists but no rating yet (matches not yet played) — use zero rating
                rating = new TeamAvatarRating(avatar.getId(), avatar.getTenantId(),
                        0, 0, 0, 0, 0, 0, 0, 0.0, 0.0,
                        false, null);
            }
            pairs.add(new AvatarWithRating(avatar, rating));
        }

        // Sort by D-33 rating comparator (best first)
        pairs.sort(Comparator.comparing(AvatarWithRating::rating));

        // Assign rank and build entries
        List<GroupTableEntry> entries = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            AvatarWithRating pair = pairs.get(i);
            TeamAvatar avatar = pair.avatar();
            TeamAvatarRating rating = pair.rating();

            String teamDesc = resolveAvatarDescription(avatar, teamDescriptionById);

            entries.add(new GroupTableEntry(
                    i + 1,          // rank (1-based)
                    avatar.getId(),
                    teamDesc,
                    avatar.getGroupNumber(),
                    avatar.getGroupPosition(),
                    rating.getMatchCount(),
                    rating.getPoints(),
                    rating.getSetsWon(),
                    rating.getSetsLost(),
                    rating.getSetQuotient(),
                    rating.getBallsWon(),
                    rating.getBallsLost(),
                    rating.getBallQuotient(),
                    rating.isWithoutAssessment()
            ));
        }
        return entries;
    }

    /**
     * Loads a {@code teamId → description} map for all teams referenced by the avatars.
     *
     * <p>Uses the phase's {@code tournamentId} to load all teams in one SQL call (acceptable
     * for V1 scale N ≤ 10 teams).
     */
    private Map<UUID, String> loadTeamDescriptions(UUID phaseId, List<TeamAvatar> avatars) {
        if (avatars.isEmpty()) {
            return Collections.emptyMap();
        }
        // All avatars in the same phase belong to the same tournament — pick from first
        UUID tournamentId = avatars.get(0).getTournamentId();
        Map<UUID, String> result = new HashMap<>();
        for (Team team : teamRepository.findByTournamentId(tournamentId)) {
            String desc = team.getDescription() != null && !team.getDescription().isBlank()
                    ? team.getDescription()
                    : "Team " + team.getTeamNumber();
            result.put(team.getId(), desc);
        }
        return result;
    }

    /**
     * Resolves a human-readable description for a team avatar.
     *
     * <p>Priority:
     * <ol>
     *   <li>{@code avatar.description} if non-blank</li>
     *   <li>{@code teamDescriptionById[avatar.teamId]} if the team is found</li>
     *   <li>Fallback: "Group {groupNumber} Pos {groupPosition}"</li>
     * </ol>
     */
    private static String resolveAvatarDescription(TeamAvatar avatar,
                                                    Map<UUID, String> teamDescriptionById) {
        if (avatar == null) {
            return "Unknown";
        }
        if (avatar.getDescription() != null && !avatar.getDescription().isBlank()) {
            return avatar.getDescription();
        }
        String teamDesc = teamDescriptionById.get(avatar.getTeamId());
        if (teamDesc != null) {
            return teamDesc;
        }
        return "Group " + avatar.getGroupNumber() + " Pos " + avatar.getGroupPosition();
    }

    /**
     * Resolves a human-readable referee description for a match.
     *
     * <p>Priority:
     * <ol>
     *   <li>{@code match.refereeTeamId} → team description if found</li>
     *   <li>{@code match.refereeDescription} free-text</li>
     *   <li>Fallback: "—"</li>
     * </ol>
     */
    private static String resolveRefereeDescription(Match match,
                                                     Map<UUID, String> teamDescriptionById) {
        if (match.getRefereeTeamId() != null) {
            String desc = teamDescriptionById.get(match.getRefereeTeamId());
            if (desc != null) {
                return desc;
            }
        }
        if (match.getRefereeDescription() != null) {
            return match.getRefereeDescription();
        }
        return "—";
    }
}
