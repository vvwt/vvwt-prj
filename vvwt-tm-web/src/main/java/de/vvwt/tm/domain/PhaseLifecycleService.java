package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseAuditLogRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Domain service for phase lifecycle operations (E05S07).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>{@link #prepare(UUID)} — triggers steps 1–3 of the E03S12 phase preparation flow
 *       (generateMatches, optimizeSlots, assignReferees). Returns a per-step result.</li>
 *   <li>{@link #start(UUID)} — triggers step 4 of the E03S12 flow (startPhase) and transitions
 *       the tournament from PLANNED to ACTIVE (DEC-5 active_sentinel guard fires at schema level).</li>
 *   <li>{@link #advanceLap(UUID, boolean, String)} — manual lap advance fallback (AC5). Increments
 *       {@code phase.currentLapNumber}. If the current lap has unfinished matches and {@code force}
 *       is {@code false}, throws 409. On force: writes a {@code PhaseAuditLogEntry} first.</li>
 *   <li>{@link #getPhase(UUID)} — loads a phase with match counts by state and total lap count.</li>
 *   <li>{@link #listPhases(UUID)} — lists all phases for a tournament ordered by sequenceNumber.</li>
 *   <li>{@link #getSchedule(UUID)} — returns the generated match schedule organized by lap and field.</li>
 * </ul>
 *
 * <h2>Tenant scope (DEC-5, DEC-17, AC15)</h2>
 * <p>All repository calls are tenant-scoped. This service performs an explicit tenant ownership
 * check when loading a phase by ID: it verifies that the phase's tournament belongs to the active
 * tenant and throws {@link NoSuchElementException} if not — this appears to the REST layer as 404,
 * preventing information leakage across tenants.
 *
 * <h2>DEC-4 V1 amendment</h2>
 * <p>Phase preparation uses {@link PhasePreparationService} which calls slot optimization
 * in-process via {@code vvwt-worker-lib}. This is synchronous and acceptable for V1 (N ≤ 10,
 * exhaustive mode — completes in seconds).
 *
 * @see PhasePreparationService
 * @see PhasePreparationResult
 * @see PhaseAuditLogEntry
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S07.story.md">Story E05S07</a>
 */
@Service
public class PhaseLifecycleService {

    private static final Logger LOG = LoggerFactory.getLogger(PhaseLifecycleService.class);

    /** Action identifier for forced lap advances (AC5). */
    public static final String ACTION_FORCE_ADVANCE_LAP = "FORCE_ADVANCE_LAP";

    private final PhasePreparationService phasePreparationService;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final SetResultRepository setResultRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;
    private final PhaseAuditLogRepository phaseAuditLogRepository;

    public PhaseLifecycleService(PhasePreparationService phasePreparationService,
                                  PhaseRepository phaseRepository,
                                  MatchRepository matchRepository,
                                  SetResultRepository setResultRepository,
                                  TeamAvatarRepository teamAvatarRepository,
                                  TeamRepository teamRepository,
                                  TournamentRepository tournamentRepository,
                                  PhaseAuditLogRepository phaseAuditLogRepository) {
        this.phasePreparationService = phasePreparationService;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.setResultRepository = setResultRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamRepository = teamRepository;
        this.tournamentRepository = tournamentRepository;
        this.phaseAuditLogRepository = phaseAuditLogRepository;
    }

    // =========================================================================
    // prepare — AC1, AC8, AC12
    // =========================================================================

    /**
     * Executes phase preparation steps 1–3 (AC1, AC8):
     * <ol>
     *   <li>generateMatches</li>
     *   <li>optimizeSlots</li>
     *   <li>assignReferees</li>
     * </ol>
     *
     * <p>Steps are executed sequentially. If a step fails, subsequent steps are skipped and the
     * result records the failure (AC12). The response always contains per-step outcome details.
     *
     * <p>This method does NOT call {@code startPhase} (step 4 is exposed via {@link #start(UUID)}).
     *
     * @param phaseId the phase to prepare (must be PENDING)
     * @return a {@link PhasePreparationResult} with per-step outcomes; never {@code null}
     * @throws NoSuchElementException if the phase does not exist or belongs to a different tenant (AC15)
     */
    @Transactional
    public PhasePreparationResult prepare(UUID phaseId) {
        requirePhaseWithTenantScope(phaseId);

        // Step 1 — generateMatches
        try {
            phasePreparationService.generateMatches(phaseId);
        } catch (Exception e) {
            LOG.warn("prepare: generateMatches failed phaseId={} reason={}", phaseId, e.getMessage());
            return PhasePreparationResult.generateMatchesFailed(e.getMessage());
        }

        // Step 2 — optimizeSlots
        try {
            phasePreparationService.optimizeSlots(phaseId);
        } catch (Exception e) {
            LOG.warn("prepare: optimizeSlots failed phaseId={} reason={}", phaseId, e.getMessage());
            return PhasePreparationResult.optimizeSlotsFailed(e.getMessage());
        }

        // Step 3 — assignReferees
        try {
            phasePreparationService.assignReferees(phaseId);
        } catch (Exception e) {
            LOG.warn("prepare: assignReferees failed phaseId={} reason={}", phaseId, e.getMessage());
            return PhasePreparationResult.assignRefereesFailed(e.getMessage());
        }

        LOG.info("prepare: all 3 steps completed phaseId={}", phaseId);
        return PhasePreparationResult.success();
    }

    // =========================================================================
    // start — AC4, AC13
    // =========================================================================

    /**
     * Executes phase preparation step 4 (startPhase) and transitions the tournament to ACTIVE (AC4).
     *
     * <p>Preconditions (enforced by {@link PhasePreparationService#startPhase(UUID)}):
     * <ul>
     *   <li>All matches have non-null slot coordinates</li>
     *   <li>All matches have a referee assignment</li>
     * </ul>
     * If preconditions are not met, throws 409 via {@link ConflictException} (AC4).
     *
     * <p>DEC-5 single-active constraint (AC13): if another tournament is already ACTIVE for the
     * same tenant, the H2 {@code active_sentinel} unique index fires. This propagates as a
     * {@link org.springframework.dao.DataIntegrityViolationException} which the
     * {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} maps to 409.
     *
     * @param phaseId the phase to start (must be PENDING)
     * @return the phase after starting
     * @throws NoSuchElementException if the phase does not exist or belongs to a different tenant
     * @throws ConflictException      if preconditions are not met (slots/referees missing)
     */
    @Transactional
    public Phase start(UUID phaseId) {
        requirePhaseWithTenantScope(phaseId);

        try {
            phasePreparationService.startPhase(phaseId);
        } catch (IllegalStateException e) {
            throw new ConflictException("Cannot start phase: " + e.getMessage());
        }

        // Transition tournament PLANNED → ACTIVE (DEC-5 active_sentinel fires at schema level)
        Phase phase = requirePhase(phaseId);
        Tournament tournament = tournamentRepository.findById(phase.getTournamentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Tournament not found for phase " + phaseId));

        if ("PLANNED".equals(tournament.getStatus())) {
            tournament.setStatus("ACTIVE");
            tournamentRepository.save(tournament);
            LOG.info("start: tournament {} PLANNED → ACTIVE", tournament.getId());
        }

        LOG.info("start: phase {} PENDING → ACTIVE", phaseId);
        return requirePhase(phaseId);
    }

    // =========================================================================
    // advanceLap — AC5
    // =========================================================================

    /**
     * Manually advances the current lap number for the given ACTIVE phase (AC5).
     *
     * <p>This is a fallback action — laps normally auto-advance during cascade recompute
     * (E03S11 step 10) when all matches in a lap reach a terminal state.
     *
     * <p>If the current lap has unfinished matches:
     * <ul>
     *   <li>{@code force=false}: returns 409 Conflict with the count of unfinished matches</li>
     *   <li>{@code force=true}: writes a {@link PhaseAuditLogEntry} recording the override, then
     *       increments the lap counter (AC5)</li>
     * </ul>
     *
     * @param phaseId  the phase whose lap counter to advance (must be ACTIVE)
     * @param force    {@code true} to override unfinished-match check (AC5)
     * @param actorId  identity of the organiser; {@code null} in LAN mode
     * @return the phase after advancing the lap
     * @throws NoSuchElementException if the phase does not exist or belongs to a different tenant
     * @throws ConflictException      if phase is not ACTIVE, or if there are unfinished matches
     *                                and {@code force} is {@code false}
     */
    @Transactional
    public Phase advanceLap(UUID phaseId, boolean force, String actorId) {
        Phase phase = requirePhaseWithTenantScope(phaseId);

        Phase.PhaseStatus status;
        try {
            status = Phase.PhaseStatus.valueOf(phase.getStatus());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ConflictException("Phase " + phaseId + " has unknown status '" + phase.getStatus() + "'");
        }

        if (status != Phase.PhaseStatus.ACTIVE) {
            throw new ConflictException(
                    "Cannot advance lap: phase " + phaseId + " is " + status.name()
                    + " but must be ACTIVE.");
        }

        int currentLap = phase.getCurrentLapNumber();
        List<Match> matchesInCurrentLap = matchRepository.findByPhaseId(phaseId).stream()
                .filter(m -> m.getLapNumber() != null && m.getLapNumber() == currentLap)
                .collect(Collectors.toList());

        long unfinishedCount = matchesInCurrentLap.stream()
                .filter(m -> !isTerminalOrCanceled(m.getMatchState()))
                .count();

        if (unfinishedCount > 0 && !force) {
            throw new ConflictException(
                    "Cannot advance lap: current lap " + currentLap + " has " + unfinishedCount
                    + " unfinished match(es). Pass force=true to override.");
        }

        if (unfinishedCount > 0) {
            // forced — write audit log entry (AC5)
            PhaseAuditLogEntry auditEntry = new PhaseAuditLogEntry(
                    UUID.randomUUID(),
                    phase.getTenantId(),
                    phaseId,
                    ACTION_FORCE_ADVANCE_LAP,
                    currentLap,
                    (int) unfinishedCount,
                    actorId,
                    null   // DB sets DEFAULT CURRENT_TIMESTAMP
            );
            phaseAuditLogRepository.save(auditEntry);
            LOG.warn("advanceLap: FORCED lap advance phase={} lap={} unfinished={} actorId={}",
                    phaseId, currentLap, unfinishedCount, actorId);
        }

        phase.setCurrentLapNumber(currentLap + 1);
        phaseRepository.save(phase);

        LOG.info("advanceLap: phase={} lap {} → {}", phaseId, currentLap, currentLap + 1);

        // Check if the tournament is now complete (AC11)
        checkTournamentCompletion(phase.getTournamentId());

        return phase;
    }

    // =========================================================================
    // getPhase — AC2
    // =========================================================================

    /**
     * Returns phase detail with match counts by state and total lap count (AC2).
     *
     * <p>The {@code totalLapCount} is derived from the number of distinct {@code lapNumber}
     * values on matches in the phase (null lap values are excluded — they represent un-scheduled
     * matches, which should not exist after preparation).
     *
     * @param phaseId the phase to retrieve
     * @return the phase (match counts are provided separately via {@link #buildMatchCounts(UUID)})
     * @throws NoSuchElementException if the phase does not exist or belongs to a different tenant
     */
    public Phase getPhase(UUID phaseId) {
        return requirePhaseWithTenantScope(phaseId);
    }

    /**
     * Returns match counts by state for the given phase (AC2).
     *
     * @param phaseId the phase whose matches to count
     * @return a map from {@link MatchState} to count
     */
    public Map<MatchState, Long> buildMatchCounts(UUID phaseId) {
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        Map<MatchState, Long> counts = new HashMap<>();
        for (Match m : matches) {
            MatchState state = m.getMatchState();
            counts.merge(state, 1L, Long::sum);
        }
        return counts;
    }

    /**
     * Returns the total lap count for the given phase — the number of distinct non-null
     * {@code lapNumber} values across all matches (AC2).
     *
     * @param phaseId the phase to query
     * @return total number of laps; 0 if no matches have been scheduled yet
     */
    public int getTotalLapCount(UUID phaseId) {
        return (int) matchRepository.findByPhaseId(phaseId).stream()
                .map(Match::getLapNumber)
                .filter(lap -> lap != null)
                .distinct()
                .count();
    }

    // =========================================================================
    // listPhases — AC3
    // =========================================================================

    /**
     * Returns all phases for the given tournament ordered by {@code sequenceNumber} (AC3).
     *
     * @param tournamentId the tournament whose phases to list
     * @return list of phases in sequence order; never {@code null}
     * @throws NoSuchElementException if the tournament does not exist or belongs to a different tenant
     */
    public List<Phase> listPhases(UUID tournamentId) {
        // Verify tournament belongs to active tenant
        tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException("Tournament not found: " + tournamentId));

        return phaseRepository.findByTournamentId(tournamentId).stream()
                .sorted(Comparator.comparingInt(Phase::getSequenceNumber))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // getSchedule — AC6
    // =========================================================================

    /**
     * Returns the generated match schedule for the phase, organized by lap and field (AC6).
     *
     * <p>For each match, resolves the avatar description for the two playing teams and the
     * referee team description (either via the referee team's label or the free-text override).
     * Also includes any existing set results (for ACTIVE phases where scoring has begun).
     *
     * @param phaseId the phase whose schedule to retrieve
     * @return ordered list of laps, each with ordered matches by fieldNumber
     * @throws NoSuchElementException if the phase does not exist or belongs to a different tenant
     */
    public List<PhaseScheduleLap> getSchedule(UUID phaseId) {
        requirePhaseWithTenantScope(phaseId);

        List<Match> matches = matchRepository.findByPhaseId(phaseId);

        // Build avatar lookup map for description resolution
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        Map<UUID, TeamAvatar> avatarById = new HashMap<>();
        for (TeamAvatar avatar : avatars) {
            avatarById.put(avatar.getId(), avatar);
        }

        // Build team lookup map (for referee team descriptions)
        // Collect all referee team IDs from matches to avoid loading all teams
        List<UUID> refereeTeamIds = matches.stream()
                .map(Match::getRefereeTeamId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        Map<UUID, Team> teamById = new HashMap<>();
        if (!refereeTeamIds.isEmpty()) {
            // Load teams for the tournament (cost: one SQL call — acceptable for V1 scale)
            Phase phase = requirePhase(phaseId);
            for (Team team : teamRepository.findByTournamentId(phase.getTournamentId())) {
                teamById.put(team.getId(), team);
            }
        }

        // Group matches by lapNumber, then sort by fieldNumber within each lap
        Map<Integer, List<Match>> byLap = new HashMap<>();
        for (Match match : matches) {
            if (match.getLapNumber() != null) {
                byLap.computeIfAbsent(match.getLapNumber(), k -> new ArrayList<>()).add(match);
            }
        }

        // Assemble lap list sorted by lapNumber
        List<PhaseScheduleLap> laps = new ArrayList<>();
        byLap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int lapNumber = entry.getKey();
                    List<PhaseScheduleMatch> lapMatches = new ArrayList<>();

                    entry.getValue().stream()
                            .sorted(Comparator.comparingInt(m -> m.getFieldNumber() != null ? m.getFieldNumber() : 0))
                            .forEach(match -> {
                                TeamAvatar avatar1 = avatarById.get(match.getMemberAvatar1Id());
                                TeamAvatar avatar2 = avatarById.get(match.getMemberAvatar2Id());

                                String team1Desc = resolveAvatarDescription(avatar1);
                                String team2Desc = resolveAvatarDescription(avatar2);
                                String refereeDesc = resolveRefereeDescription(match, teamById);

                                List<SetResult> setResults = setResultRepository.findByMatchId(match.getId());

                                lapMatches.add(new PhaseScheduleMatch(
                                        match.getId(),
                                        match.getFieldNumber(),
                                        team1Desc,
                                        team2Desc,
                                        refereeDesc,
                                        match.getMatchState().name(),
                                        setResults
                                ));
                            });

                    laps.add(new PhaseScheduleLap(lapNumber, lapMatches));
                });

        return laps;
    }

    // =========================================================================
    // Nested value types for schedule response
    // =========================================================================

    /**
     * One lap's worth of matches in the schedule (AC6).
     *
     * @param lapNumber the lap index
     * @param matches   matches in this lap, ordered by fieldNumber
     */
    public record PhaseScheduleLap(int lapNumber, List<PhaseScheduleMatch> matches) {}

    /**
     * One match in the schedule (AC6).
     *
     * @param matchId          match identifier
     * @param fieldNumber      court number
     * @param team1Description avatar description for team 1
     * @param team2Description avatar description for team 2
     * @param refereeDescription referee description
     * @param matchState       match state name (e.g., "ENABLED", "INPROGRESS", "FINISHED_WINNER1")
     * @param setResults       set results if scoring has begun
     */
    public record PhaseScheduleMatch(
            UUID matchId,
            Integer fieldNumber,
            String team1Description,
            String team2Description,
            String refereeDescription,
            String matchState,
            List<SetResult> setResults
    ) {}

    // =========================================================================
    // checkTournamentCompletion — AC11
    // =========================================================================

    /**
     * Checks whether all phases in the given tournament are COMPLETED and, if so, transitions
     * the tournament to COMPLETED status (AC11).
     *
     * <p>Called after {@link #advanceLap} and by {@link de.vvwt.tm.infrastructure.web.DomainEventBridge}
     * on each {@link de.vvwt.tm.domain.event.MatchResultChangedEvent} (cascade auto-advance path).
     *
     * <p>No-op if the tournament is already COMPLETED or not yet ACTIVE.
     *
     * @param tournamentId the tournament to check
     */
    @Transactional
    public void checkTournamentCompletion(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId).orElse(null);
        if (tournament == null || !"ACTIVE".equals(tournament.getStatus())) {
            return;
        }

        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        if (phases.isEmpty()) {
            return;
        }

        boolean allCompleted = phases.stream()
                .allMatch(p -> Phase.PhaseStatus.COMPLETED.name().equals(p.getStatus()));

        if (allCompleted) {
            tournament.setStatus("COMPLETED");
            tournamentRepository.save(tournament);
            LOG.info("checkTournamentCompletion: tournament {} → COMPLETED (all {} phases done)",
                    tournamentId, phases.size());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Phase requirePhaseWithTenantScope(UUID phaseId) {
        Phase phase = requirePhase(phaseId);
        // Tenant scope check (AC15): verify the phase's tournament belongs to active tenant
        // If tournament not found for this tenant, throw 404 to avoid info leakage
        tournamentRepository.findById(phase.getTournamentId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Phase not found: " + phaseId));
        return phase;
    }

    private Phase requirePhase(UUID phaseId) {
        return phaseRepository.findById(phaseId)
                .orElseThrow(() -> new NoSuchElementException("Phase not found: " + phaseId));
    }

    private static boolean isTerminalOrCanceled(MatchState state) {
        return switch (state) {
            case FINISHED_STANDOFF, FINISHED_WINNER1, FINISHED_WINNER2, CANCELED -> true;
            default -> false;
        };
    }

    private static String resolveAvatarDescription(TeamAvatar avatar) {
        if (avatar == null) {
            return "Unknown";
        }
        if (avatar.getDescription() != null && !avatar.getDescription().isBlank()) {
            return avatar.getDescription();
        }
        return "Group " + avatar.getGroupNumber() + " Pos " + avatar.getGroupPosition();
    }

    private static String resolveRefereeDescription(Match match, Map<UUID, Team> teamById) {
        if (match.getRefereeTeamId() != null) {
            Team refereeTeam = teamById.get(match.getRefereeTeamId());
            if (refereeTeam != null) {
                return refereeTeam.getDescription() != null
                        ? refereeTeam.getDescription()
                        : "Team " + refereeTeam.getTeamNumber();
            }
        }
        if (match.getRefereeDescription() != null) {
            return match.getRefereeDescription();
        }
        return "—";
    }
}
