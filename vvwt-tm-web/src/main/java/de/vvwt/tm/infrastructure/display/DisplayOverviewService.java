package de.vvwt.tm.infrastructure.display;

import de.vvwt.tm.infrastructure.display.dto.DisplayGroupStandingsResponse;
import de.vvwt.tm.infrastructure.display.dto.DisplayMatchesResponse;
import de.vvwt.tm.infrastructure.display.dto.DisplayPhaseOverviewResponse;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.SetResult;
import de.vvwt.tm.tournament.SetResultRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
import de.vvwt.tm.tournament.internal.DeviceRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the display device overview endpoints (E07S04).
 *
 * <h2>Responsibilities (ACs covered)</h2>
 *
 * <ul>
 *   <li>AC1 — Phase overview: active phase with groups summary
 *   <li>AC2 — Matches by lap: all matches for current or specified lap
 *   <li>AC3 — Group standings: D-33 sorted rankings per group
 *   <li>AC4 — Device token authentication: validates DISPLAY device type and active status
 *   <li>AC5 — Tenant scoping: device token resolves tenant; TenantContext set by interceptor
 *   <li>AC6 — Preparation preview: PENDING phase with matches → preparationPreview=true
 *   <li>AC7 — No active phase → {@link NoActivePhaseException}
 *   <li>AC8 — All data from local H2 only — no external calls (DEC-16)
 *   <li>AC9 — Error handling per E05S03 format (via GlobalExceptionHandler)
 *   <li>AC10 — i18n via GlobalExceptionHandler message keys
 *   <li>AC11 — Read-only (no mutating calls from this service)
 * </ul>
 *
 * <h2>Device token authentication (AC4)</h2>
 *
 * <p>A valid display token must:
 *
 * <ol>
 *   <li>Exist in the {@code devices} table for the active tenant
 *   <li>Have {@code device_type = 'DISPLAY'} — scoring tablet tokens are rejected
 *   <li>Have status {@code REGISTERED} or {@code ASSIGNED} — not {@code DISCONNECTED}
 * </ol>
 *
 * Any validation failure → {@link UnauthorizedException} → HTTP 401 (AC4).
 *
 * <h2>Preparation preview (AC6)</h2>
 *
 * <p>A phase in PREPARATION (status=PENDING in the domain model) may have matches already generated
 * by slot-optimization. If at least one match with a non-null lapNumber exists for the phase,
 * {@code preparationPreview=true} is set. The matches endpoint (AC2) returns the generated
 * schedule. Group standings (AC3) return teams with zero scores.
 *
 * <h2>Phase detection (AC1, AC7)</h2>
 *
 * <p>The service looks for a phase with status {@code ACTIVE} first. If none exists, it looks for a
 * phase with status {@code PENDING} that has at least one match (preparation preview). If neither
 * exists, {@link NoActivePhaseException} is thrown.
 *
 * @see de.vvwt.tm.infrastructure.web.GlobalExceptionHandler
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S04.story.md">Story
 *     E07S04</a>
 */
@Service
public class DisplayOverviewService {

    private static final Logger log = LoggerFactory.getLogger(DisplayOverviewService.class);

    /** Device type constant for display devices (E07S01). */
    private static final String DEVICE_TYPE_DISPLAY = "DISPLAY";

    /** Device status constants from the devices table. */
    private static final String STATUS_REGISTERED = "REGISTERED";

    private static final String STATUS_ASSIGNED = "ASSIGNED";

    /** Tournament status constant for active tournaments. */
    private static final String TOURNAMENT_STATUS_ACTIVE = "ACTIVE";

    /** Phase status constants. */
    private static final String PHASE_STATUS_ACTIVE = "ACTIVE";

    private static final String PHASE_STATUS_PENDING = "PENDING";

    // -------------------------------------------------------------------------
    // Match display status mapping
    // -------------------------------------------------------------------------

    private static final String DISPLAY_STATUS_PENDING = "PENDING";
    private static final String DISPLAY_STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String DISPLAY_STATUS_COMPLETED = "COMPLETED";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final DeviceRepository deviceRepository;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamAvatarRatingRepository teamAvatarRatingRepository;
    private final TeamRepository teamRepository;
    private final SetResultRepository setResultRepository;

    public DisplayOverviewService(
            DeviceRepository deviceRepository,
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamAvatarRatingRepository teamAvatarRatingRepository,
            TeamRepository teamRepository,
            SetResultRepository setResultRepository) {
        this.deviceRepository = deviceRepository;
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamAvatarRatingRepository = teamAvatarRatingRepository;
        this.teamRepository = teamRepository;
        this.setResultRepository = setResultRepository;
    }

    // =========================================================================
    // AC1 — GET /api/display/overview
    // =========================================================================

    /**
     * Returns the current phase overview for the display device's tenant (AC1, AC6, AC7).
     *
     * <p>Phase detection order:
     *
     * <ol>
     *   <li>Active phase (status=ACTIVE) — normal operating mode
     *   <li>Pending phase with at least one match (slot opt ran) → preparationPreview=true (AC6)
     *   <li>None found → {@link NoActivePhaseException} (AC7)
     * </ol>
     *
     * @param deviceToken the display device's token (AC4)
     * @return phase overview response (never null)
     * @throws UnauthorizedException if the token is invalid or not a DISPLAY device (AC4)
     * @throws NoActivePhaseException if no active phase exists (AC7)
     */
    @Transactional(readOnly = true)
    public DisplayPhaseOverviewResponse getPhaseOverview(String deviceToken) {
        validateDisplayDeviceToken(deviceToken);

        Tournament activeTournament = findActiveTournament();
        PhaseWithPreview phaseWithPreview = findActiveOrPreparationPhase(activeTournament.getId());
        Phase phase = phaseWithPreview.phase();
        boolean preparationPreview = phaseWithPreview.preparationPreview();

        List<Match> matchesInPhase = matchRepository.findByPhaseId(phase.getId());
        List<TeamAvatar> avatarsInPhase = teamAvatarRepository.findByPhaseId(phase.getId());

        int lapCount = computeLapCount(matchesInPhase);
        List<DisplayPhaseOverviewResponse.GroupSummary> groups =
                buildGroupSummaries(avatarsInPhase);

        log.debug(
                "[display] Phase overview: phaseId={} status={} lapCount={} preparationPreview={}",
                phase.getId(),
                phase.getStatus(),
                lapCount,
                preparationPreview);

        return new DisplayPhaseOverviewResponse(
                phase.getId(),
                phase.getTenantId(), // E07S06 AC1: send tenantId so SPA can build WS topic
                phase.getDescription(),
                phase.getStatus(),
                lapCount,
                phase.getCurrentLapNumber(),
                activeTournament.getFieldCount(),
                preparationPreview,
                groups);
    }

    // =========================================================================
    // AC2 — GET /api/display/overview/matches
    // =========================================================================

    /**
     * Returns all matches for the given lap (or current lap if omitted) (AC2).
     *
     * <p>Match status is mapped from the domain {@link MatchState} to a display-friendly string:
     * OPEN/ENABLED → {@code PENDING}, INPROGRESS/ONCHECK → {@code IN_PROGRESS}, FINISHED_* /
     * CANCELED → {@code COMPLETED}.
     *
     * @param deviceToken the display device's token (AC4)
     * @param lap the lap number to query; {@code null} = current lap (AC2)
     * @return matches response (never null)
     * @throws UnauthorizedException if the token is invalid or not a DISPLAY device (AC4)
     * @throws NoActivePhaseException if no active phase exists (AC7)
     */
    @Transactional(readOnly = true)
    public DisplayMatchesResponse getMatchesByLap(String deviceToken, Integer lap) {
        validateDisplayDeviceToken(deviceToken);

        Tournament activeTournament = findActiveTournament();
        PhaseWithPreview phaseWithPreview = findActiveOrPreparationPhase(activeTournament.getId());
        Phase phase = phaseWithPreview.phase();

        int effectiveLap = (lap != null) ? lap : phase.getCurrentLapNumber();

        List<Match> allMatchesInPhase = matchRepository.findByPhaseId(phase.getId());
        List<Match> matchesForLap =
                allMatchesInPhase.stream()
                        .filter(m -> m.getLapNumber() != null && m.getLapNumber() == effectiveLap)
                        .toList();

        // Build a lookup map: avatarId → team (for name resolution)
        List<TeamAvatar> avatarsInPhase = teamAvatarRepository.findByPhaseId(phase.getId());
        Map<UUID, TeamAvatar> avatarById = buildAvatarLookup(avatarsInPhase);

        // Collect all teamIds for batch lookup
        List<UUID> teamIds = avatarsInPhase.stream().map(TeamAvatar::getTeamId).distinct().toList();
        Map<UUID, Team> teamById = buildTeamLookup(teamIds);

        // Also build referee team lookup from all teams in the tournament
        List<Team> allTeamsInTournament =
                teamRepository.findByTournamentId(activeTournament.getId());
        Map<UUID, Team> allTeamById = buildTeamLookupFromList(allTeamsInTournament);

        List<DisplayMatchesResponse.MatchEntry> entries = new ArrayList<>();
        for (Match match : matchesForLap) {
            String teamAName = resolveTeamName(match.getMemberAvatar1Id(), avatarById, teamById);
            String teamBName = resolveTeamName(match.getMemberAvatar2Id(), avatarById, teamById);
            String refereeTeamName = resolveRefereeTeamName(match.getRefereeTeamId(), allTeamById);
            String displayStatus = mapMatchStateToDisplayStatus(match.getMatchState());

            List<SetResult> setResults = setResultRepository.findByMatchId(match.getId());
            List<DisplayMatchesResponse.SetResultEntry> setEntries =
                    setResults.stream()
                            .sorted((a, b) -> Integer.compare(a.getSetIndex(), b.getSetIndex()))
                            .map(
                                    sr ->
                                            new DisplayMatchesResponse.SetResultEntry(
                                                    sr.getSetIndex(),
                                                    sr.getTeam1Points(),
                                                    sr.getTeam2Points()))
                            .toList();

            entries.add(
                    new DisplayMatchesResponse.MatchEntry(
                            match.getId(),
                            match.getFieldNumber(),
                            teamAName,
                            teamBName,
                            setEntries,
                            displayStatus,
                            refereeTeamName));
        }

        log.debug(
                "[display] Matches for phase={} lap={}: count={}",
                phase.getId(),
                effectiveLap,
                entries.size());

        return new DisplayMatchesResponse(phase.getId(), effectiveLap, entries);
    }

    // =========================================================================
    // AC3 — GET /api/display/overview/groups
    // =========================================================================

    /**
     * Returns group standings for the current active phase (AC3).
     *
     * <p>Standings are sorted per D-33: points DESC, set quotient DESC, ball quotient DESC,
     * withoutAssessment rows always last. Position is 1-indexed within each group.
     *
     * @param deviceToken the display device's token (AC4)
     * @return group standings response (never null)
     * @throws UnauthorizedException if the token is invalid or not a DISPLAY device (AC4)
     * @throws NoActivePhaseException if no active phase exists (AC7)
     */
    @Transactional(readOnly = true)
    public DisplayGroupStandingsResponse getGroupStandings(String deviceToken) {
        validateDisplayDeviceToken(deviceToken);

        Tournament activeTournament = findActiveTournament();
        PhaseWithPreview phaseWithPreview = findActiveOrPreparationPhase(activeTournament.getId());
        Phase phase = phaseWithPreview.phase();

        List<TeamAvatar> avatarsInPhase = teamAvatarRepository.findByPhaseId(phase.getId());

        // Build lookup: avatarId → rating
        Map<UUID, TeamAvatarRating> ratingByAvatarId = new LinkedHashMap<>();
        for (TeamAvatar avatar : avatarsInPhase) {
            Optional<TeamAvatarRating> ratingOpt =
                    teamAvatarRatingRepository.findById(avatar.getId());
            // If no rating row exists (e.g., tournament not yet started), use a zero-score default
            TeamAvatarRating rating = ratingOpt.orElseGet(() -> buildZeroRating(avatar));
            ratingByAvatarId.put(avatar.getId(), rating);
        }

        // Team name lookup
        List<UUID> teamIds = avatarsInPhase.stream().map(TeamAvatar::getTeamId).distinct().toList();
        Map<UUID, Team> teamById = buildTeamLookup(teamIds);

        // Group by groupNumber and sort within each group by D-33 criteria
        TreeMap<Integer, List<TeamAvatar>> avatarsByGroup = new TreeMap<>();
        for (TeamAvatar avatar : avatarsInPhase) {
            avatarsByGroup
                    .computeIfAbsent(avatar.getGroupNumber(), k -> new ArrayList<>())
                    .add(avatar);
        }

        List<DisplayGroupStandingsResponse.GroupStandings> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<TeamAvatar>> entry : avatarsByGroup.entrySet()) {
            int groupNumber = entry.getKey();
            List<TeamAvatar> groupAvatars = entry.getValue();

            // Sort by rating using D-33 compareTo (ascending = best first)
            groupAvatars.sort(
                    (a, b) -> {
                        TeamAvatarRating ratingA = ratingByAvatarId.get(a.getId());
                        TeamAvatarRating ratingB = ratingByAvatarId.get(b.getId());
                        return ratingA.compareTo(ratingB);
                    });

            List<DisplayGroupStandingsResponse.TeamRanking> rankings = new ArrayList<>();
            for (int i = 0; i < groupAvatars.size(); i++) {
                TeamAvatar avatar = groupAvatars.get(i);
                TeamAvatarRating rating = ratingByAvatarId.get(avatar.getId());
                String teamName =
                        teamById.containsKey(avatar.getTeamId())
                                ? teamById.get(avatar.getTeamId()).getDescription()
                                : "Team " + avatar.getTeamId();

                rankings.add(
                        new DisplayGroupStandingsResponse.TeamRanking(
                                i + 1,
                                teamName,
                                rating.getPoints(),
                                rating.getSetsWon(),
                                rating.getSetsLost(),
                                rating.getBallsWon(),
                                rating.getBallsLost()));
            }
            groups.add(new DisplayGroupStandingsResponse.GroupStandings(groupNumber, rankings));
        }

        log.debug(
                "[display] Group standings for phase={}: groups={}", phase.getId(), groups.size());

        return new DisplayGroupStandingsResponse(phase.getId(), groups);
    }

    // =========================================================================
    // AC4 — Device token validation
    // =========================================================================

    /**
     * Validates that the token belongs to an active DISPLAY device for the current tenant (AC4).
     *
     * <p>Validation checks:
     *
     * <ol>
     *   <li>Token exists in {@code devices} for the active tenant
     *   <li>{@code device_type = 'DISPLAY'}
     *   <li>{@code status} is {@code REGISTERED} or {@code ASSIGNED}
     * </ol>
     *
     * @param deviceToken the token to validate
     * @throws UnauthorizedException if any check fails (AC4)
     */
    private void validateDisplayDeviceToken(String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new UnauthorizedException("Missing or empty device token");
        }

        Device device =
                deviceRepository
                        .findByDeviceToken(deviceToken)
                        .orElseThrow(
                                () -> new UnauthorizedException("Invalid or unknown device token"));

        if (!DEVICE_TYPE_DISPLAY.equals(device.getDeviceType())) {
            throw new UnauthorizedException(
                    "Device token is not for a display device (type="
                            + device.getDeviceType()
                            + ")");
        }

        if (!STATUS_REGISTERED.equals(device.getStatus())
                && !STATUS_ASSIGNED.equals(device.getStatus())) {
            throw new UnauthorizedException(
                    "Display device is not active (status=" + device.getStatus() + ")");
        }
    }

    // =========================================================================
    // Phase detection helpers
    // =========================================================================

    /**
     * Returns the active tournament for the current tenant (AC1, AC7).
     *
     * @throws NoActivePhaseException if no active tournament exists
     */
    private Tournament findActiveTournament() {
        return tournamentRepository.findAll().stream()
                .filter(t -> TOURNAMENT_STATUS_ACTIVE.equals(t.getStatus()))
                .findFirst()
                .orElseThrow(NoActivePhaseException::new);
    }

    /**
     * Returns the active or preparation-preview phase for the given tournament (AC1, AC6, AC7).
     *
     * <p>Checks phases in order:
     *
     * <ol>
     *   <li>Status=ACTIVE → normal phase (preparationPreview=false)
     *   <li>Status=PENDING with at least one match with a non-null lapNumber →
     *       preparationPreview=true
     *   <li>None → {@link NoActivePhaseException}
     * </ol>
     *
     * @param tournamentId the tournament to query
     * @return the phase and preview flag (never null)
     * @throws NoActivePhaseException if no active or preparation phase exists
     */
    private PhaseWithPreview findActiveOrPreparationPhase(UUID tournamentId) {
        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);

        // First: look for ACTIVE phase
        Optional<Phase> activePhase =
                phases.stream().filter(p -> PHASE_STATUS_ACTIVE.equals(p.getStatus())).findFirst();
        if (activePhase.isPresent()) {
            return new PhaseWithPreview(activePhase.get(), false);
        }

        // Second: look for PENDING phase that has at least one slot-optimized match (AC6)
        for (Phase phase : phases) {
            if (PHASE_STATUS_PENDING.equals(phase.getStatus())) {
                List<Match> matches = matchRepository.findByPhaseId(phase.getId());
                boolean hasScheduledMatches =
                        matches.stream().anyMatch(m -> m.getLapNumber() != null);
                if (hasScheduledMatches) {
                    return new PhaseWithPreview(phase, true);
                }
            }
        }

        throw new NoActivePhaseException();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Computes the total number of distinct lap numbers in the phase's matches.
     *
     * <p>A lap is considered present if at least one match has a non-null {@code lapNumber}. In a
     * preparation-preview phase, this reflects the generated schedule.
     *
     * @param matches all matches for the phase
     * @return distinct lap count (0 if no matches are scheduled)
     */
    private int computeLapCount(List<Match> matches) {
        return (int)
                matches.stream()
                        .map(Match::getLapNumber)
                        .filter(lap -> lap != null)
                        .distinct()
                        .count();
    }

    /**
     * Builds group summaries from the phase's TeamAvatars, grouping by {@code groupNumber} and
     * counting team slots.
     *
     * @param avatars all team avatars for the phase
     * @return ordered list of group summaries (by group number ascending)
     */
    private List<DisplayPhaseOverviewResponse.GroupSummary> buildGroupSummaries(
            List<TeamAvatar> avatars) {
        TreeMap<Integer, Integer> groupTeamCount = new TreeMap<>();
        for (TeamAvatar avatar : avatars) {
            groupTeamCount.merge(avatar.getGroupNumber(), 1, Integer::sum);
        }
        List<DisplayPhaseOverviewResponse.GroupSummary> groups = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : groupTeamCount.entrySet()) {
            groups.add(
                    new DisplayPhaseOverviewResponse.GroupSummary(
                            entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableList(groups);
    }

    /**
     * Builds an avatarId → TeamAvatar lookup map.
     *
     * @param avatars the team avatars to index
     * @return lookup map (never null)
     */
    private Map<UUID, TeamAvatar> buildAvatarLookup(List<TeamAvatar> avatars) {
        Map<UUID, TeamAvatar> map = new LinkedHashMap<>();
        for (TeamAvatar avatar : avatars) {
            map.put(avatar.getId(), avatar);
        }
        return map;
    }

    /**
     * Builds a teamId → Team lookup map by loading each team from the repository.
     *
     * @param teamIds the team UUIDs to load
     * @return lookup map (never null)
     */
    private Map<UUID, Team> buildTeamLookup(List<UUID> teamIds) {
        Map<UUID, Team> map = new LinkedHashMap<>();
        for (UUID teamId : teamIds) {
            teamRepository.findById(teamId).ifPresent(t -> map.put(t.getId(), t));
        }
        return map;
    }

    /**
     * Builds a teamId → Team lookup map from an already-loaded list.
     *
     * @param teams the teams to index
     * @return lookup map (never null)
     */
    private Map<UUID, Team> buildTeamLookupFromList(List<Team> teams) {
        Map<UUID, Team> map = new LinkedHashMap<>();
        for (Team team : teams) {
            map.put(team.getId(), team);
        }
        return map;
    }

    /**
     * Resolves the team name for the given avatar ID from the lookup maps.
     *
     * @param avatarId the team avatar UUID
     * @param avatarById lookup map for avatars
     * @param teamById lookup map for teams
     * @return the team description, or a fallback if not found
     */
    private String resolveTeamName(
            UUID avatarId, Map<UUID, TeamAvatar> avatarById, Map<UUID, Team> teamById) {
        TeamAvatar avatar = avatarById.get(avatarId);
        if (avatar == null) {
            return "Unknown";
        }
        Team team = teamById.get(avatar.getTeamId());
        return team != null ? team.getDescription() : "Unknown";
    }

    /**
     * Resolves the referee team name from the team lookup map.
     *
     * @param refereeTeamId the referee team UUID (may be null)
     * @param teamById lookup map for teams
     * @return the team description, or {@code null} if no referee is assigned
     */
    private String resolveRefereeTeamName(UUID refereeTeamId, Map<UUID, Team> teamById) {
        if (refereeTeamId == null) {
            return null;
        }
        Team team = teamById.get(refereeTeamId);
        return team != null ? team.getDescription() : null;
    }

    /**
     * Maps a {@link MatchState} to a display-friendly status string (AC2).
     *
     * <ul>
     *   <li>{@code OPEN} / {@code ENABLED} → {@code PENDING}
     *   <li>{@code INPROGRESS} / {@code ONCHECK} → {@code IN_PROGRESS}
     *   <li>{@code FINISHED_WINNER1} / {@code FINISHED_WINNER2} / {@code FINISHED_STANDOFF} /
     *       {@code CANCELED} → {@code COMPLETED}
     * </ul>
     *
     * @param state the domain match state
     * @return display status string
     */
    private String mapMatchStateToDisplayStatus(MatchState state) {
        return switch (state) {
            case OPEN, ENABLED -> DISPLAY_STATUS_PENDING;
            case INPROGRESS, ONCHECK -> DISPLAY_STATUS_IN_PROGRESS;
            case FINISHED_WINNER1, FINISHED_WINNER2, FINISHED_STANDOFF, CANCELED ->
                    DISPLAY_STATUS_COMPLETED;
        };
    }

    /**
     * Creates a zero-score {@link TeamAvatarRating} for an avatar that has no rating row yet.
     *
     * <p>This handles AC6 (preparation phase preview): during preparation, no matches have been
     * played so no ratings exist. The display shows zero scores for all teams.
     *
     * @param avatar the avatar to create a zero rating for
     * @return a transient (not persisted) zero-score rating
     */
    private TeamAvatarRating buildZeroRating(TeamAvatar avatar) {
        TeamAvatarRating rating = new TeamAvatarRating();
        rating.setAvatarId(avatar.getId());
        rating.setTenantId(avatar.getTenantId());
        rating.setMatchCount(0);
        rating.setSetCount(0);
        rating.setPoints(0);
        rating.setSetsWon(0);
        rating.setSetsLost(0);
        rating.setBallsWon(0);
        rating.setBallsLost(0);
        rating.setSetQuotient(0.0);
        rating.setBallQuotient(0.0);
        rating.setWithoutAssessment(false);
        return rating;
    }

    // =========================================================================
    // Private value types
    // =========================================================================

    /**
     * Pairs a {@link Phase} with a flag indicating whether it is in preparation-preview mode (AC6).
     *
     * @param phase the detected phase
     * @param preparationPreview {@code true} if the phase is PENDING but has slot-optimized matches
     */
    private record PhaseWithPreview(Phase phase, boolean preparationPreview) {}
}
