package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain service for match result correction operations (E05S11).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>{@link #getMatchDetail(UUID)} — returns the full match detail including teams, referee,
 *       format, state, and all set results (AC1).</li>
 *   <li>{@link #correctSet(UUID, int, int, int, String)} — corrects an existing set result
 *       by calling {@link CascadeRecomputeService#registerMatchResult} with the admin-correction
 *       path, then returns the updated match detail (AC2).</li>
 *   <li>{@link #enterNewSet(UUID, int, int, String)} — enters the result for the next unplayed
 *       set, auto-determining the setIndex from the existing set count (AC3).</li>
 * </ul>
 *
 * <h2>Cascade delegation (AC4–AC6)</h2>
 * <p>Both {@code correctSet} and {@code enterNewSet} delegate to
 * {@link CascadeRecomputeService#registerMatchResult}, which executes the full 13-step
 * cascade: SetResult INSERT/UPDATE (AC4 audit_log write in step 2), MatchOutcome recompute
 * (AC5), match state re-derivation (AC5), TeamAvatarRating refresh (AC5), and
 * MatchResultChangedEvent emission (AC6 WebSocket).
 *
 * <h2>Audit source (DEC-14, AC4)</h2>
 * <p>The {@link SetResultInput#reason()} field is set to {@code "admin_correction"} for all
 * admin-path calls. This appears as the {@code reason} column in the {@code audit_log} table,
 * distinguishing admin corrections from other entry paths (e.g., tablet entry).
 *
 * <h2>Tenant scope (DEC-5, DEC-17, AC11)</h2>
 * <p>All methods verify that the match's tournament belongs to the active tenant.
 * Cross-tenant access returns {@link NoSuchElementException} (→ 404) to prevent info leakage.
 *
 * @see CascadeRecomputeService
 * @see SetResultInput
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S11.story.md">Story E05S11</a>
 */
@Service
public class MatchCorrectionService {

    private static final Logger LOG = LoggerFactory.getLogger(MatchCorrectionService.class);

    /** Reason value written to audit_log for admin-path corrections (AC4, DEC-14). */
    static final String ADMIN_CORRECTION_REASON = "admin_correction";

    private final MatchRepository matchRepository;
    private final SetResultRepository setResultRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;
    private final CascadeRecomputeService cascadeRecomputeService;

    public MatchCorrectionService(MatchRepository matchRepository,
                                   SetResultRepository setResultRepository,
                                   TeamAvatarRepository teamAvatarRepository,
                                   TeamRepository teamRepository,
                                   TournamentRepository tournamentRepository,
                                   CascadeRecomputeService cascadeRecomputeService) {
        this.matchRepository = matchRepository;
        this.setResultRepository = setResultRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamRepository = teamRepository;
        this.tournamentRepository = tournamentRepository;
        this.cascadeRecomputeService = cascadeRecomputeService;
    }

    // =========================================================================
    // getMatchDetail — AC1
    // =========================================================================

    /**
     * Returns the full detail for the given match including team descriptions, referee,
     * match format, match state, and all set results (AC1).
     *
     * <p>The match is verified to belong to the active tenant before any data is returned.
     *
     * @param matchId the match to retrieve
     * @return the assembled {@link MatchDetail}
     * @throws NoSuchElementException if the match does not exist or belongs to another tenant (AC11)
     */
    @Transactional(readOnly = true)
    public MatchDetail getMatchDetail(UUID matchId) {
        Match match = requireMatchWithTenantScope(matchId);
        return assembleMatchDetail(match);
    }

    // =========================================================================
    // correctSet — AC2
    // =========================================================================

    /**
     * Corrects an existing set result and triggers the full 13-step cascade recompute (AC2).
     *
     * <p>The cascade service handles:
     * <ul>
     *   <li>Score validation via {@link de.vvwt.tm.domain.rules.SetValidationRule} (AC10)</li>
     *   <li>SetResult UPDATE with old/new values</li>
     *   <li>audit_log INSERT with {@code reason = "admin_correction"} (AC4)</li>
     *   <li>MatchOutcome + match state recompute (AC5)</li>
     *   <li>TeamAvatarRating refresh for both teams (AC5)</li>
     *   <li>MatchResultChangedEvent emission → WebSocket MATCH_RESULT_CHANGED (AC6)</li>
     * </ul>
     *
     * @param matchId      the match whose set to correct
     * @param setIndex     the 0-based index of the set to correct
     * @param team1Points  new score for team 1
     * @param team2Points  new score for team 2
     * @param actorId      authenticated user identity; {@code null} in LAN mode
     * @return the updated {@link MatchDetail} after cascade completes (AC2)
     * @throws NoSuchElementException if the match does not exist or belongs to another tenant (AC11)
     * @throws ValidationException    if the scores are invalid for the match format (AC10)
     */
    @Transactional
    public MatchDetail correctSet(UUID matchId, int setIndex,
                                   int team1Points, int team2Points,
                                   String actorId) {
        requireMatchWithTenantScope(matchId);

        SetResultInput input = new SetResultInput(
                matchId, setIndex, team1Points, team2Points, actorId, ADMIN_CORRECTION_REASON);

        LOG.info("correctSet: matchId={} setIndex={} team1={} team2={} actorId={}",
                matchId, setIndex, team1Points, team2Points, actorId);

        cascadeRecomputeService.registerMatchResult(input);

        return assembleMatchDetail(requireMatchWithTenantScope(matchId));
    }

    // =========================================================================
    // enterNewSet — AC3
    // =========================================================================

    /**
     * Enters a result for the next unplayed set, auto-determining the setIndex (AC3).
     *
     * <p>The setIndex is the current number of existing set results for this match
     * (i.e., {@code findByMatchId(matchId).size()}). This is safe because:
     * <ul>
     *   <li>Sets are entered in order (setIndex = 0, 1, 2, …)</li>
     *   <li>The cascade service's SetValidationRule validates the score before persisting</li>
     * </ul>
     *
     * @param matchId      the match for which to enter the next set
     * @param team1Points  score for team 1 in the new set
     * @param team2Points  score for team 2 in the new set
     * @param actorId      authenticated user identity; {@code null} in LAN mode
     * @return the updated {@link MatchDetail} after cascade completes (AC3)
     * @throws NoSuchElementException if the match does not exist or belongs to another tenant (AC11)
     * @throws ValidationException    if the scores are invalid for the match format (AC10)
     */
    @Transactional
    public MatchDetail enterNewSet(UUID matchId, int team1Points, int team2Points, String actorId) {
        requireMatchWithTenantScope(matchId);

        int nextSetIndex = setResultRepository.findByMatchId(matchId).size();

        SetResultInput input = new SetResultInput(
                matchId, nextSetIndex, team1Points, team2Points, actorId, ADMIN_CORRECTION_REASON);

        LOG.info("enterNewSet: matchId={} setIndex={} team1={} team2={} actorId={}",
                matchId, nextSetIndex, team1Points, team2Points, actorId);

        cascadeRecomputeService.registerMatchResult(input);

        return assembleMatchDetail(requireMatchWithTenantScope(matchId));
    }

    // =========================================================================
    // Domain value type for match detail (AC1)
    // =========================================================================

    /**
     * Full detail for a match returned by the admin correction API (AC1).
     *
     * @param matchId            the match UUID
     * @param phaseId            the phase this match belongs to
     * @param tournamentId       the tournament this match belongs to
     * @param team1Description   human-readable description for team 1 (avatar description or fallback)
     * @param team2Description   human-readable description for team 2
     * @param refereeDescription human-readable description for the referee team (or {@code "—"} if none)
     * @param matchFormat        match format name (e.g., {@code "BEST_OF_3"}) from Tournament entity
     * @param matchState         match state name (e.g., {@code "ENABLED"}, {@code "FINISHED_WINNER1"})
     * @param setLimit           maximum number of sets for this match
     * @param setResults         set results in ascending setIndex order; empty if no sets played yet
     */
    public record MatchDetail(
            UUID matchId,
            UUID phaseId,
            UUID tournamentId,
            String team1Description,
            String team2Description,
            String refereeDescription,
            String matchFormat,
            String matchState,
            int setLimit,
            List<SetResultEntry> setResults
    ) {}

    /**
     * A single set result entry in the match detail (AC1).
     *
     * @param setIndex    0-based index of the set within the match
     * @param team1Points score for team 1 in this set
     * @param team2Points score for team 2 in this set
     * @param setState    set state name (e.g., {@code "WINNER1"}, {@code "OPEN"})
     */
    public record SetResultEntry(
            int setIndex,
            int team1Points,
            int team2Points,
            String setState
    ) {}

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Match requireMatchWithTenantScope(UUID matchId) {
        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new NoSuchElementException("Match not found: " + matchId));

        // Tenant scope check (AC11, DEC-5): verify the match's tournament belongs to active tenant.
        // TournamentRepository.findById is tenant-scoped — returns empty if different tenant.
        tournamentRepository.findById(match.getTournamentId())
                .orElseThrow(() -> new NoSuchElementException("Match not found: " + matchId));

        return match;
    }

    private MatchDetail assembleMatchDetail(Match match) {
        // Resolve team descriptions via TeamAvatar → description fallback
        List<TeamAvatar> phaseAvatars = teamAvatarRepository.findByPhaseId(match.getPhaseId());

        String team1Description = resolveAvatarDescription(phaseAvatars, match.getMemberAvatar1Id());
        String team2Description = resolveAvatarDescription(phaseAvatars, match.getMemberAvatar2Id());

        // Resolve referee description
        String refereeDescription = resolveRefereeDescription(match);

        // Resolve match format from Tournament
        Tournament tournament = tournamentRepository.findById(match.getTournamentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Tournament not found for match " + match.getId()));
        String matchFormat = tournament.getMatchFormat();

        // Load set results ordered by setIndex
        List<SetResult> rawSetResults = new ArrayList<>(
                setResultRepository.findByMatchId(match.getId()));
        rawSetResults.sort(Comparator.comparingInt(SetResult::getSetIndex));

        List<SetResultEntry> setResultEntries = new ArrayList<>();
        for (SetResult sr : rawSetResults) {
            setResultEntries.add(new SetResultEntry(
                    sr.getSetIndex(),
                    sr.getTeam1Points(),
                    sr.getTeam2Points(),
                    sr.getSetState().name()
            ));
        }

        return new MatchDetail(
                match.getId(),
                match.getPhaseId(),
                match.getTournamentId(),
                team1Description,
                team2Description,
                refereeDescription,
                matchFormat,
                match.getMatchState().name(),
                match.getSetLimit(),
                setResultEntries
        );
    }

    private String resolveAvatarDescription(List<TeamAvatar> phaseAvatars, UUID avatarId) {
        if (avatarId == null) {
            return "Unknown";
        }
        Optional<TeamAvatar> avatar = phaseAvatars.stream()
                .filter(a -> avatarId.equals(a.getId()))
                .findFirst();
        if (avatar.isEmpty()) {
            return "Unknown";
        }
        TeamAvatar av = avatar.get();
        if (av.getDescription() != null && !av.getDescription().isBlank()) {
            return av.getDescription();
        }
        return "Group " + av.getGroupNumber() + " Pos " + av.getGroupPosition();
    }

    private String resolveRefereeDescription(Match match) {
        if (match.getRefereeTeamId() != null) {
            Optional<Team> refereeTeam = teamRepository.findById(match.getRefereeTeamId());
            if (refereeTeam.isPresent()) {
                Team t = refereeTeam.get();
                return t.getDescription() != null ? t.getDescription()
                        : "Team " + t.getTeamNumber();
            }
        }
        if (match.getRefereeDescription() != null) {
            return match.getRefereeDescription();
        }
        return "\u2014"; // em-dash: no referee assigned
    }
}
