package de.vvwt.tm.display.internal;

import de.vvwt.tm.display.DisplayGroupStandingsResponse;
import de.vvwt.tm.display.DisplayMatchesResponse;
import de.vvwt.tm.display.DisplayOverviewService;
import de.vvwt.tm.display.DisplayPhaseOverviewResponse;
import de.vvwt.tm.display.NoActivePhaseException;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link DisplayOverviewService} (DEC-35, E25S01).
 *
 * <p>Resides in {@code de.vvwt.tm.display.internal} per DEC-35 §impl-in-internal rule. Named {@code
 * DefaultDisplayOverviewService} per DEC-35 naming canon (no {@code I}-prefix on interface).
 *
 * <p>All public methods are read-only {@link Transactional} per legacy audit (i) C-3 preservation.
 * The 8-arg constructor signature is preserved verbatim per C-3: {@code DeviceRepository,
 * TournamentRepository, PhaseRepository, MatchRepository, TeamAvatarRepository,
 * TeamAvatarRatingRepository, TeamRepository, SetResultRepository}. All 8 are
 * tournament-root-package types per audit (i) empirical verification (E25 Discovery, commit {@code
 * 1aded97}).
 *
 * <p>Authored Q-1a TDD RED-first per DEC-22 Iron Law + DEC-41 §3 hierarchy item (1). Legacy {@code
 * infrastructure.display.DisplayOverviewService} deleted at AC-DELETE-LEGACY-FIRST (D-7 Coexistence
 * Option γ). Legacy code NOT inspected during authoring per AC-NO-LEGACY-CODE-REFERENCE.
 *
 * @see DisplayOverviewService
 * @see de.vvwt.tm.display.DisplayPhaseOverviewResponse
 * @see de.vvwt.tm.display.DisplayMatchesResponse
 * @see de.vvwt.tm.display.DisplayGroupStandingsResponse
 * @see DEC-22
 * @see DEC-35
 * @see E25S01
 */
@Service
public class DefaultDisplayOverviewService implements DisplayOverviewService {

    // -------------------------------------------------------------------------
    // MatchState → display status mapping constants (AC-MATCHES-BY-LAP-EXPLICIT-LAP)
    // -------------------------------------------------------------------------

    private static final String DISPLAY_STATUS_PENDING = "PENDING";
    private static final String DISPLAY_STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String DISPLAY_STATUS_COMPLETED = "COMPLETED";

    // -------------------------------------------------------------------------
    // Dependencies (8-arg constructor, verbatim per C-3)
    // -------------------------------------------------------------------------

    private final DeviceRepository deviceRepository;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamAvatarRatingRepository teamAvatarRatingRepository;
    private final TeamRepository teamRepository;
    private final SetResultRepository setResultRepository;

    /**
     * 8-arg constructor — signature preserved verbatim per C-3 signature-preservation.
     *
     * <p>All 8 parameter types are in the {@code tournament} ROOT package per audit (i) empirical
     * verification (E25 Discovery, commit {@code 1aded97}).
     *
     * @param deviceRepository for device token validation
     * @param tournamentRepository for resolving the active tournament by tenant
     * @param phaseRepository for finding ACTIVE or slot-ready PENDING phases
     * @param matchRepository for loading matches within a phase
     * @param teamAvatarRepository for team avatar (slot) resolution
     * @param teamAvatarRatingRepository for D-33 sort data per avatar
     * @param teamRepository for resolving team names from avatar → team references
     * @param setResultRepository for loading set scores per match
     */
    public DefaultDisplayOverviewService(
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
    // DisplayOverviewService interface implementation
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Validates the device token (4 branches per AC-VALIDATE-TOKEN-BRANCH-1 through -4), then
     * resolves the active phase for this tenant's tournament. Returns the phase overview DTO with
     * group summaries derived from TeamAvatar groupNumber counts. Sets {@code
     * preparationPreview=true} when phase is PENDING with at least one slot-optimized match.
     */
    @Override
    @Transactional(readOnly = true)
    public DisplayPhaseOverviewResponse getPhaseOverview(String deviceToken) {
        Device device = validateDisplayDeviceToken(deviceToken);
        Phase phase = resolveActiveOrPreviewPhase(device.getTenantId());
        Tournament tournament = resolveTournamentForPhase(phase);
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phase.getId());
        List<Match> matches = matchRepository.findByPhaseId(phase.getId());

        boolean preparationPreview =
                "PENDING".equals(phase.getStatus())
                        && matches.stream().anyMatch(m -> m.getLapNumber() != null);

        // Derive lapCount from the number of distinct lap values in scheduled matches
        long lapCount =
                matches.stream().map(Match::getLapNumber).filter(l -> l != null).distinct().count();

        // Build group summaries: count avatars per groupNumber
        Map<Integer, Long> countByGroup =
                avatars.stream()
                        .collect(
                                Collectors.groupingBy(
                                        TeamAvatar::getGroupNumber, Collectors.counting()));

        List<DisplayPhaseOverviewResponse.GroupSummary> groups =
                countByGroup.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(
                                e ->
                                        new DisplayPhaseOverviewResponse.GroupSummary(
                                                e.getKey(), e.getValue().intValue()))
                        .collect(Collectors.toList());

        return new DisplayPhaseOverviewResponse(
                phase.getId(),
                device.getTenantId(),
                phase.getDescription(),
                phase.getStatus(),
                (int) lapCount,
                phase.getCurrentLapNumber(),
                tournament.getFieldCount(),
                preparationPreview,
                groups);
    }

    /**
     * {@inheritDoc}
     *
     * <p>When {@code lap} is {@code null}, uses {@code phase.currentLapNumber} as the effective lap
     * (AC-MATCHES-BY-LAP-CURRENT-LAP-DEFAULT). Resolves team names via TeamAvatar → Team lookup.
     * Maps MatchState to display status strings per AC-MATCHES-BY-LAP-EXPLICIT-LAP.
     */
    @Override
    @Transactional(readOnly = true)
    public DisplayMatchesResponse getMatchesByLap(String deviceToken, Integer lap) {
        Device device = validateDisplayDeviceToken(deviceToken);
        Phase phase = resolveActiveOrPreviewPhase(device.getTenantId());
        resolveTournamentForPhase(phase); // validates tournament exists

        int effectiveLap = (lap != null) ? lap : phase.getCurrentLapNumber();

        List<Match> allMatches = matchRepository.findByPhaseId(phase.getId());
        List<Match> lapMatches =
                allMatches.stream()
                        .filter(m -> Integer.valueOf(effectiveLap).equals(m.getLapNumber()))
                        .collect(Collectors.toList());

        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phase.getId());
        Map<UUID, TeamAvatar> avatarById =
                avatars.stream().collect(Collectors.toMap(TeamAvatar::getId, Function.identity()));

        Tournament tournament = resolveTournamentForPhase(phase);
        List<Team> allTeams = teamRepository.findByTournamentId(tournament.getId());
        Map<UUID, Team> teamById =
                allTeams.stream().collect(Collectors.toMap(Team::getId, Function.identity()));

        List<DisplayMatchesResponse.MatchEntry> entries = new ArrayList<>();
        for (Match m : lapMatches) {
            String teamAName = resolveTeamName(m.getMemberAvatar1Id(), avatarById, teamById);
            String teamBName = resolveTeamName(m.getMemberAvatar2Id(), avatarById, teamById);

            List<SetResult> setResults = setResultRepository.findByMatchId(m.getId());
            List<DisplayMatchesResponse.SetResultEntry> setEntries =
                    setResults.stream()
                            .sorted((a, b) -> Integer.compare(a.getSetIndex(), b.getSetIndex()))
                            .map(
                                    sr ->
                                            new DisplayMatchesResponse.SetResultEntry(
                                                    sr.getSetIndex(),
                                                    sr.getTeam1Points(),
                                                    sr.getTeam2Points()))
                            .collect(Collectors.toList());

            String displayStatus = mapMatchStatus(m.getMatchState());

            entries.add(
                    new DisplayMatchesResponse.MatchEntry(
                            m.getId(),
                            m.getFieldNumber(),
                            teamAName,
                            teamBName,
                            setEntries,
                            displayStatus,
                            null)); // referee team not tracked in this service scope
        }

        return new DisplayMatchesResponse(phase.getId(), effectiveLap, entries);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Loads all TeamAvatars for the active phase, resolves their TeamAvatarRating rows (zero
     * default when absent per AC-GROUP-STANDINGS-ZERO-RATING-FALLBACK), sorts per D-33, and assigns
     * 1-based positions within each group.
     */
    @Override
    @Transactional(readOnly = true)
    public DisplayGroupStandingsResponse getGroupStandings(String deviceToken) {
        Device device = validateDisplayDeviceToken(deviceToken);
        Phase phase = resolveActiveOrPreviewPhase(device.getTenantId());

        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phase.getId());

        // Group avatars by groupNumber
        Map<Integer, List<TeamAvatar>> avatarsByGroup =
                avatars.stream()
                        .collect(
                                Collectors.groupingBy(
                                        TeamAvatar::getGroupNumber, Collectors.toList()));

        List<DisplayGroupStandingsResponse.GroupStandings> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<TeamAvatar>> entry :
                avatarsByGroup.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .collect(Collectors.toList())) {
            List<TeamAvatar> groupAvatars = entry.getValue();

            // Build (avatar, rating) pairs with zero-default fallback
            List<AvatarWithRating> ranked = new ArrayList<>();
            for (TeamAvatar ta : groupAvatars) {
                TeamAvatarRating rating =
                        teamAvatarRatingRepository.findById(ta.getId()).orElseGet(this::zeroRating);
                ranked.add(new AvatarWithRating(ta, rating));
            }

            // Sort per D-33 using TeamAvatarRating.compareTo
            ranked.sort((a, b) -> a.rating().compareTo(b.rating()));

            List<DisplayGroupStandingsResponse.TeamRanking> rankings = new ArrayList<>();
            for (int i = 0; i < ranked.size(); i++) {
                AvatarWithRating ar = ranked.get(i);
                Optional<Team> team = teamRepository.findById(ar.avatar().getTeamId());
                String teamName = team.map(Team::getDescription).orElse("?");
                TeamAvatarRating r = ar.rating();
                rankings.add(
                        new DisplayGroupStandingsResponse.TeamRanking(
                                i + 1, // 1-indexed position
                                teamName,
                                r.getPoints(),
                                r.getSetsWon(),
                                r.getSetsLost(),
                                r.getBallsWon(),
                                r.getBallsLost()));
            }

            groups.add(new DisplayGroupStandingsResponse.GroupStandings(entry.getKey(), rankings));
        }

        return new DisplayGroupStandingsResponse(phase.getId(), groups);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Validates the display device token — 4 branches per AC-VALIDATE-TOKEN-BRANCH-1 through -4.
     *
     * @param deviceToken the token to validate
     * @return the validated {@link Device}
     * @throws UnauthorizedException if the token is null/blank (line 395 branch), not found (line
     *     402 lambda branch), wrong device type (line 405 branch), or device not active (line 413
     *     branch)
     */
    private Device validateDisplayDeviceToken(String deviceToken) {
        // AC-VALIDATE-TOKEN-BRANCH-1-MISSING: null or blank token
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new UnauthorizedException("Missing or empty device token");
        }

        // AC-VALIDATE-TOKEN-BRANCH-2-UNKNOWN-LAMBDA: lambda inside orElseThrow
        Device device =
                deviceRepository
                        .findByDeviceToken(deviceToken)
                        .orElseThrow(
                                () -> new UnauthorizedException("Invalid or unknown device token"));

        // AC-VALIDATE-TOKEN-BRANCH-3-WRONG-TYPE
        if (!"DISPLAY".equals(device.getDeviceType())) {
            throw new UnauthorizedException(
                    "Device token is not for a display device (type="
                            + device.getDeviceType()
                            + ")");
        }

        // AC-VALIDATE-TOKEN-BRANCH-4-INACTIVE-STATUS
        boolean active =
                Device.STATUS_REGISTERED.equals(device.getStatus())
                        || Device.STATUS_ASSIGNED.equals(device.getStatus());
        if (!active) {
            throw new UnauthorizedException(
                    "Display device is not active (status=" + device.getStatus() + ")");
        }

        return device;
    }

    /**
     * Resolves the active phase (or PENDING-with-scheduled-matches phase) for the given tenant.
     *
     * <p>Algorithm:
     *
     * <ol>
     *   <li>Load all tournaments; find one that is ACTIVE.
     *   <li>Load phases for that tournament; find an ACTIVE phase.
     *   <li>If no ACTIVE phase, look for a PENDING phase that has at least one match with a
     *       non-null {@code lapNumber} (slot-optimization has run → preparationPreview).
     *   <li>If neither exists, throw {@link NoActivePhaseException}.
     * </ol>
     *
     * @param tenantId the tenant UUID (from the device)
     * @return the resolved phase
     * @throws NoActivePhaseException if no displayable phase is available
     */
    private Phase resolveActiveOrPreviewPhase(UUID tenantId) {
        List<Tournament> tournaments = tournamentRepository.findAll();

        // Find the first ACTIVE tournament
        Optional<Tournament> activeTournament =
                tournaments.stream().filter(t -> "ACTIVE".equals(t.getStatus())).findFirst();

        if (activeTournament.isEmpty()) {
            throw new NoActivePhaseException();
        }

        List<Phase> phases = phaseRepository.findByTournamentId(activeTournament.get().getId());

        // Prefer ACTIVE phase
        Optional<Phase> activePhase =
                phases.stream().filter(p -> "ACTIVE".equals(p.getStatus())).findFirst();
        if (activePhase.isPresent()) {
            return activePhase.get();
        }

        // Fall back to PENDING phase with scheduled matches (preparationPreview)
        for (Phase phase : phases) {
            if ("PENDING".equals(phase.getStatus())) {
                List<Match> matches = matchRepository.findByPhaseId(phase.getId());
                boolean hasScheduled = matches.stream().anyMatch(m -> m.getLapNumber() != null);
                if (hasScheduled) {
                    return phase;
                }
            }
        }

        throw new NoActivePhaseException();
    }

    /**
     * Loads the tournament that owns the given phase.
     *
     * @param phase the phase
     * @return the owning tournament
     * @throws IllegalStateException if not found (should not happen in consistent DB state)
     */
    private Tournament resolveTournamentForPhase(Phase phase) {
        return tournamentRepository
                .findById(phase.getTournamentId())
                .orElseGet(
                        () -> {
                            List<Tournament> all = tournamentRepository.findAll();
                            return all.stream()
                                    .filter(t -> "ACTIVE".equals(t.getStatus()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "No active tournament found for phase "
                                                                    + phase.getId()));
                        });
    }

    /**
     * Resolves the team display name for a given avatar ID.
     *
     * @param avatarId the avatar UUID from the match
     * @param avatarById map from avatar ID to TeamAvatar
     * @param teamById map from team ID to Team
     * @return the team's description, or {@code "?"} if not found
     */
    private String resolveTeamName(
            UUID avatarId, Map<UUID, TeamAvatar> avatarById, Map<UUID, Team> teamById) {
        if (avatarId == null) {
            return "?";
        }
        TeamAvatar avatar = avatarById.get(avatarId);
        if (avatar == null) {
            return "?";
        }
        Team team = teamById.get(avatar.getTeamId());
        return team != null ? team.getDescription() : "?";
    }

    /**
     * Maps a {@link MatchState} to the display status string used by the Svelte SPA.
     *
     * <ul>
     *   <li>{@code OPEN/ENABLED} -&gt; {@code "PENDING"}
     *   <li>{@code INPROGRESS/ONCHECK} -&gt; {@code "IN_PROGRESS"}
     *   <li>{@code FINISHED_WINNER1/FINISHED_WINNER2/FINISHED_STANDOFF/CANCELED} -&gt; {@code
     *       "COMPLETED"}
     * </ul>
     *
     * @param state the match state
     * @return the display status string
     */
    private String mapMatchStatus(MatchState state) {
        if (state == null) {
            return DISPLAY_STATUS_PENDING;
        }
        return switch (state) {
            case OPEN, ENABLED -> DISPLAY_STATUS_PENDING;
            case INPROGRESS, ONCHECK -> DISPLAY_STATUS_IN_PROGRESS;
            case FINISHED_WINNER1, FINISHED_WINNER2, FINISHED_STANDOFF, CANCELED ->
                    DISPLAY_STATUS_COMPLETED;
        };
    }

    /**
     * Creates a zero-score {@link TeamAvatarRating} for teams with no rating row yet
     * (AC-GROUP-STANDINGS-ZERO-RATING-FALLBACK).
     *
     * @return a transient rating with all numeric fields = 0 and {@code withoutAssessment=false}
     */
    private TeamAvatarRating zeroRating() {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setPoints(0);
        r.setSetsWon(0);
        r.setSetsLost(0);
        r.setBallsWon(0);
        r.setBallsLost(0);
        r.setWithoutAssessment(false);
        r.setSetQuotient(0.0);
        r.setBallQuotient(0.0);
        return r;
    }

    /**
     * Internal value type pairing a TeamAvatar slot with its rating (for D-33 sorting).
     *
     * @param avatar the TeamAvatar slot
     * @param rating the TeamAvatarRating (possibly zero-default)
     */
    private record AvatarWithRating(TeamAvatar avatar, TeamAvatarRating rating) {}
}
