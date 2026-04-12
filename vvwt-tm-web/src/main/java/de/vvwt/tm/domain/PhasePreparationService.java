package de.vvwt.tm.domain;

import de.vvwt.tm.domain.generator.MatchGenerator;
import de.vvwt.tm.domain.referee.RefereeAssigner;
import de.vvwt.tm.domain.repo.MatchOutcomeRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.domain.rules.TournamentRuleResolver;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spring service that exposes the four-step phase preparation flow (D-29, AC1).
 *
 * <p>Each of the four methods is a distinct, idempotent, transactional service call:
 * <ol>
 *   <li>{@link #generateMatches(UUID)} — generate the match schedule for the phase</li>
 *   <li>{@link #optimizeSlots(UUID)} — assign lap/field coordinates to each match</li>
 *   <li>{@link #assignReferees(UUID)} — assign referee teams to each match</li>
 *   <li>{@link #startPhase(UUID)} — transition the phase from PENDING to ACTIVE</li>
 * </ol>
 *
 * <h2>Phase lifecycle (AC7)</h2>
 * <p>Only the {@code PENDING → ACTIVE} transition is managed here. The
 * {@code ACTIVE → COMPLETED} transition is owned by the cascade service (E03S11, step 10).
 * Backward transitions (COMPLETED → ACTIVE, ACTIVE → PENDING) are not supported in V1.
 *
 * <h2>Idempotency (AC6)</h2>
 * <p>Steps 1–3 ({@code generateMatches}, {@code optimizeSlots}, {@code assignReferees}) are
 * safe to re-run in PENDING state. {@code startPhase} is NOT idempotent — calling it twice
 * throws {@link IllegalStateException} because the phase is already ACTIVE.
 *
 * <h2>Tenant scope (DEC-5, DEC-17, AC22)</h2>
 * <p>All repository calls go through tenant-scoped repositories (E03S05). Cross-tenant access
 * is structurally impossible.
 *
 * @see de.vvwt.tm.slotopt.SlotOptimizationClient
 * @see RefereeAssigner
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S12.story.md">Story E03S12</a>
 */
@Service
public class PhasePreparationService {

    private static final Logger LOG = LoggerFactory.getLogger(PhasePreparationService.class);

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final MatchOutcomeRepository matchOutcomeRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentRuleResolver tournamentRuleResolver;
    private final SlotOptimizationClient slotOptimizationClient;
    private final RefereeAssigner refereeAssigner;

    /**
     * Constructs the service. Spring injects all collaborators.
     *
     * @param phaseRepository          repository for {@link Phase} entities
     * @param matchRepository          repository for {@link Match} entities
     * @param matchOutcomeRepository   repository for {@link MatchOutcome} entities (cascade delete)
     * @param teamAvatarRepository     repository for {@link TeamAvatar} entities
     * @param tournamentRepository     repository for {@link Tournament} entities
     * @param tournamentRuleResolver   strategy resolver for match generator
     * @param slotOptimizationClient   E04 client (or fallback) for slot assignment
     * @param refereeAssigner          E03S10 referee assignment service
     */
    public PhasePreparationService(PhaseRepository phaseRepository,
                                   MatchRepository matchRepository,
                                   MatchOutcomeRepository matchOutcomeRepository,
                                   TeamAvatarRepository teamAvatarRepository,
                                   TournamentRepository tournamentRepository,
                                   TournamentRuleResolver tournamentRuleResolver,
                                   SlotOptimizationClient slotOptimizationClient,
                                   RefereeAssigner refereeAssigner) {
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.matchOutcomeRepository = matchOutcomeRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.tournamentRepository = tournamentRepository;
        this.tournamentRuleResolver = tournamentRuleResolver;
        this.slotOptimizationClient = slotOptimizationClient;
        this.refereeAssigner = refereeAssigner;
    }

    // =========================================================================
    // Step 1 — generateMatches (AC2, AC6, AC9, AC10, AC18, AC19)
    // =========================================================================

    /**
     * Generates the match schedule for the given phase (D-29 step 1, AC2).
     *
     * <p>This method is idempotent: if matches already exist for the phase, they are deleted
     * first (cascade: set_result and match_outcome rows are also removed if present) before
     * new matches are inserted. The audit log is NOT cleared — the append-only invariant
     * is preserved (AC2 note).
     *
     * @param phaseId the phase for which to generate matches; must not be {@code null}
     * @throws IllegalArgumentException if the phase does not exist (AC17)
     * @throws IllegalStateException    if the phase is not in PENDING status (AC18)
     */
    @Transactional
    public void generateMatches(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        // Load and validate phase
        Phase phase = requirePhase(phaseId);
        requireStatus(phase, Phase.PhaseStatus.PENDING, "generateMatches");

        // Load avatars
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);

        // Load tournament and resolve match generator
        Tournament tournament = requireTournament(phase.getTournamentId());
        MatchGenerator generator = tournamentRuleResolver.resolveMatchGenerator(tournament);

        // Idempotent-delete: clear existing matches (and cascade: outcomes, set_results)
        // set_result rows are deleted via FK cascade in the schema (phase_id FK on set_result).
        // match_outcome rows must be explicitly deleted because there is no schema-level
        // cascade from match to match_outcome.
        List<Match> existingMatches = matchRepository.findByPhaseId(phaseId);
        if (!existingMatches.isEmpty()) {
            for (Match existing : existingMatches) {
                matchOutcomeRepository.deleteByMatchId(existing.getId());
            }
            matchRepository.deleteByPhaseId(phaseId);
            LOG.info("generateMatches: phase={} — deleted {} existing matches before re-generation",
                    phaseId, existingMatches.size());
        }

        // Generate new matches
        List<Match> newMatches = generator.generate(phase, avatars);

        // Insert all new matches
        matchRepository.saveAll(newMatches);

        LOG.info("generateMatches: phase={}, avatars={}, generated {} matches, generator={}",
                phaseId, avatars.size(), newMatches.size(), generator.getBeanId());
    }

    // =========================================================================
    // Step 2 — optimizeSlots (AC3, AC6, AC11, AC12a, AC18)
    // =========================================================================

    /**
     * Assigns slot coordinates ({@code lap_number}, {@code field_number}) to every match in
     * the phase (D-29 step 2, AC3).
     *
     * <p>This method is idempotent: if matches already have non-null slot coordinates, they are
     * overwritten by the optimization result. Delegates to the {@link SlotOptimizationClient}
     * (E04 real implementation or {@code FallbackSlotOptimizationClient} for V1).
     *
     * @param phaseId the phase whose matches should receive slot assignments
     * @throws IllegalArgumentException if the phase does not exist (AC17)
     * @throws IllegalStateException    if the phase is not PENDING (AC18), or if no matches
     *                                  have been generated yet (AC3 precondition)
     */
    @Transactional
    public void optimizeSlots(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        Phase phase = requirePhase(phaseId);
        requireStatus(phase, Phase.PhaseStatus.PENDING, "optimizeSlots");

        long matchCount = matchRepository.count();  // count() returns total; use findByPhaseId
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "optimizeSlots: phase " + phaseId
                    + " has no matches — run generateMatches first.");
        }

        slotOptimizationClient.optimize(phaseId);

        LOG.info("optimizeSlots: phase={}, {} matches slot-optimized via {}",
                phaseId, matches.size(), slotOptimizationClient.getClass().getSimpleName());
    }

    // =========================================================================
    // Step 3 — assignReferees (AC4, AC6, AC12, AC12b, AC18)
    // =========================================================================

    /**
     * Assigns referee teams to every eligible match in the phase (D-29 step 3, AC4).
     *
     * <p>Delegates to {@link RefereeAssigner#assignReferees(UUID)} from E03S10.
     * Precondition: all matches must have non-null slot coordinates (enforced by
     * {@link RefereeAssigner} itself — AC4 note).
     *
     * @param phaseId the phase whose matches should have referees assigned
     * @throws IllegalArgumentException if the phase does not exist (AC17)
     * @throws IllegalStateException    if the phase is not PENDING (AC18), or if any match
     *                                  lacks slot coordinates
     */
    @Transactional
    public void assignReferees(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        Phase phase = requirePhase(phaseId);
        requireStatus(phase, Phase.PhaseStatus.PENDING, "assignReferees");

        // Verify precondition: all matches must have slot coordinates
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        for (Match match : matches) {
            if (match.getLapNumber() == null || match.getFieldNumber() == null) {
                throw new IllegalStateException(
                        "assignReferees: match " + match.getId() + " in phase " + phaseId
                        + " has no slot coordinates. Run optimizeSlots first.");
            }
        }

        refereeAssigner.assignReferees(phaseId);

        LOG.info("assignReferees: phase={}, {} matches processed", phaseId, matches.size());
    }

    // =========================================================================
    // Step 4 — startPhase (AC5, AC13, AC14, AC15, AC18, AC21)
    // =========================================================================

    /**
     * Transitions the phase from PENDING to ACTIVE and enables all matches (D-29 step 4, AC5).
     *
     * <p>This method is NOT idempotent — calling it when the phase is already ACTIVE or
     * COMPLETED throws {@link IllegalStateException} (AC6 note, AC7).
     *
     * <p>Preconditions verified before transition:
     * <ul>
     *   <li>Every match has non-null {@code lap_number} and {@code field_number}</li>
     *   <li>Every match has EITHER non-null {@code referee_team_id} OR
     *       non-null {@code referee_description} (per D-35)</li>
     * </ul>
     * If any precondition fails, throws {@link IllegalStateException} naming the failing
     * matches (AC5, AC14).
     *
     * <p>On success:
     * <ul>
     *   <li>{@code phase.status} transitions to {@code ACTIVE}</li>
     *   <li>{@code phase.current_lap_number} is set to 0 (if not already)</li>
     *   <li>All matches in the phase transition from {@code OPEN(0)} to {@code ENABLED(10)}</li>
     * </ul>
     *
     * @param phaseId the phase to start
     * @throws IllegalArgumentException if the phase does not exist (AC17)
     * @throws IllegalStateException    if the phase is not PENDING, or if preconditions fail (AC5, AC7)
     */
    @Transactional
    public void startPhase(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        Phase phase = requirePhase(phaseId);
        requireStatus(phase, Phase.PhaseStatus.PENDING, "startPhase");

        List<Match> matches = matchRepository.findByPhaseId(phaseId);

        // Validate preconditions (AC5)
        List<UUID> missingSlots = new ArrayList<>();
        List<UUID> missingReferees = new ArrayList<>();

        for (Match match : matches) {
            if (match.getLapNumber() == null || match.getFieldNumber() == null) {
                missingSlots.add(match.getId());
            }
            boolean hasRefereeTeam = match.getRefereeTeamId() != null;
            boolean hasRefereeDescription = match.getRefereeDescription() != null;
            if (!hasRefereeTeam && !hasRefereeDescription) {
                missingReferees.add(match.getId());
            }
        }

        if (!missingSlots.isEmpty()) {
            throw new IllegalStateException(
                    "startPhase: phase " + phaseId + " has " + missingSlots.size()
                    + " match(es) without slot coordinates: " + missingSlots
                    + ". Run optimizeSlots first.");
        }

        if (!missingReferees.isEmpty()) {
            throw new IllegalStateException(
                    "startPhase: phase " + phaseId + " has " + missingReferees.size()
                    + " match(es) without referee assignment: " + missingReferees
                    + ". Run assignReferees first, or set a manual override via"
                    + " Match.referee_description.");
        }

        // Transition phase: PENDING → ACTIVE
        String statusBefore = phase.getStatus();
        phase.setStatus(Phase.PhaseStatus.ACTIVE.name());
        phase.setCurrentLapNumber(0);
        phaseRepository.save(phase);

        // Enable all matches: OPEN(0) → ENABLED(10)
        int enabledCount = 0;
        for (Match match : matches) {
            match.setMatchState(MatchState.ENABLED);
            matchRepository.save(match);
            enabledCount++;
        }

        LOG.info("startPhase: phase={}, status {} → ACTIVE, currentLapNumber=0, {} matches OPEN → ENABLED",
                phaseId, statusBefore, enabledCount);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Phase requirePhase(UUID phaseId) {
        return phaseRepository.findById(phaseId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Phase not found: " + phaseId));
    }

    private Tournament requireTournament(UUID tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tournament not found: " + tournamentId));
    }

    private void requireStatus(Phase phase, Phase.PhaseStatus expected, String operationName) {
        Phase.PhaseStatus actual;
        try {
            actual = Phase.PhaseStatus.valueOf(phase.getStatus());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException(
                    operationName + ": phase " + phase.getId()
                    + " has unknown status '" + phase.getStatus() + "'");
        }
        if (actual != expected) {
            throw new IllegalStateException(
                    operationName + ": phase " + phase.getId()
                    + " must be in status " + expected.name()
                    + " but is " + actual.name());
        }
    }
}
