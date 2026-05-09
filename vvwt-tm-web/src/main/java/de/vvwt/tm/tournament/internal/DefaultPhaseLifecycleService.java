package de.vvwt.tm.tournament.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.MatchLockdownService;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.Phase.PhaseStatus;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link PhaseLifecycleService} (DEC-35, E48S06, E48S17, E51S05).
 *
 * <p>Drives each phase through its lifecycle status transitions. Every method acquires a
 * per-tournament pessimistic DB row-lock via {@link TournamentRepository#findByIdForUpdate(UUID)}
 * as its FIRST READ — serialising concurrent transitions on the same tournament aggregate root per
 * DEC-37 Clause B.
 *
 * <p>{@link #transition(UUID, PhaseStatus, String)} (E51S05) is the single-source-of-truth method
 * for all status mutations; it validates every call against the {@link #ALLOWED} transition table
 * (DEC-55 D-4). The {@code ASSIGNED → ACTIVE "start"} transition additionally enforces the
 * activation-guard {@code !tournament.optimize OR phase.optimized OR section.gameMode ==
 * "siegerehrung"} (DEC-55 D-6 amended by DEC-59 Clause F, E51S18). The {@code ObjectMapper} is
 * injected to parse {@code tournament.draftJson} for the gameMode lookup.
 *
 * <p>{@link #prepare(UUID)} transitions PENDING → PREPARED (E48S17 / E51S06 rollback of E48S21).
 * Pure status flip only — avatar persistence (E51S02) and match generation (E51S03) are separate
 * pipeline steps. The E48S21 {@code prepare(UUID, List)} delegate to commitTransition has been
 * rolled back per DEC-55 D-10.
 *
 * <p>{@link #start(UUID)} requires {@code ASSIGNED} status (E51S06 refactor from E48S17's PREPARED)
 * because {@code commitTransition} now sets ASSIGNED (not PREPARED). Additionally checks
 * predecessor completion: if sequenceNumber &gt; 1, the predecessor phase (sequenceNumber - 1) must
 * be {@code COMPLETED}.
 *
 * <p>{@link #complete(UUID)} verifies that all matches are in terminal states before allowing the
 * ACTIVE → COMPLETED transition (AC-TEST-PHASE-COMPLETE-ALL-FINISHED-RED).
 *
 * <p>{@link #forceComplete(UUID)} delegates to {@link MatchLockdownService} for match bulk-cancel —
 * reusing E48S04 logic without duplication (AC-IMPL-FORCE-COMPLETE-REUSES-LOCKDOWN).
 *
 * <p>{@link PhaseStatusChangedEvent} is published on every successful status change
 * (AC-IMPL-PUBLISH-PHASE-STATUS-EVENT).
 *
 * @see PhaseLifecycleService
 * @see MatchLockdownService
 * @see <a href="DEC-35">DEC-35 — package layout: impl in .internal</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-55">DEC-55 D-4 + D-6 — ASSIGNED status, transition-table, activation-guard</a>
 * @see <a href="DEC-59">DEC-59 Clause F — activation-guard gameMode OR-term (siegerehrung exempt)</a>
 * @see <a href="E48S06">E48S06 — Phase-Lifecycle Service</a>
 * @see <a href="E48S17">E48S17 — PREPARED enum + prepare() + start() refactor</a>
 * @see <a href="E51S05">E51S05 — transition-table + activation-guard implementation</a>
 * @see <a href="E51S18">E51S18 — operationalize DEC-59 (Clause F injection)</a>
 */
@Service("tmPhaseLifecycleService")
public class DefaultPhaseLifecycleService implements PhaseLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPhaseLifecycleService.class);

    // =========================================================================
    // Verb-encoded transition table (DEC-55 D-4, AC-IMPL-TRANSITION-TABLE-IN-LIFECYCLE-SERVICE)
    // =========================================================================

    /**
     * Immutable record representing a directed transition edge in the phase lifecycle graph.
     *
     * <p>The {@code verb} discriminates edges that share the same {@code (source, target)} pair,
     * e.g. {@code ACTIVE → COMPLETED "complete"} vs {@code ACTIVE → COMPLETED "force-complete"}.
     * Stored as {@code String} constants per established Spring/Java idioms.
     *
     * @param target the target {@link PhaseStatus}
     * @param verb the action verb that labels this edge
     * @see <a href="DEC-55">DEC-55 D-4 — verb-encoded transition table</a>
     * @see <a href="E51S05">E51S05 — AC-IMPL-TRANSITION-TABLE-IN-LIFECYCLE-SERVICE</a>
     */
    record TransitionEdge(PhaseStatus target, String verb) {}

    /**
     * Single-source-of-truth phase transition table (DEC-55 D-4, E51S05).
     *
     * <p>Maps each source {@link PhaseStatus} to the set of outbound {@link TransitionEdge}s. Every
     * call to {@link #transition(UUID, PhaseStatus, String)} validates the requested {@code
     * (source, target, verb)} triple against this map.
     *
     * <pre>
     * PENDING  → PREPARED  "match-gen-done"
     * PREPARED → ASSIGNED  "assign"
     * ASSIGNED → ASSIGNED  "re-assign"    (idempotent self-loop)
     * ASSIGNED → ACTIVE    "start"        (activation-guard applies — DEC-55 D-6)
     * ACTIVE   → COMPLETED "complete"
     * ACTIVE   → COMPLETED "force-complete"
     * </pre>
     */
    private static final Map<PhaseStatus, Set<TransitionEdge>> ALLOWED =
            Map.of(
                    PhaseStatus.PENDING,
                    Set.of(new TransitionEdge(PhaseStatus.PREPARED, "match-gen-done")),
                    PhaseStatus.PREPARED,
                    Set.of(new TransitionEdge(PhaseStatus.ASSIGNED, "assign")),
                    PhaseStatus.ASSIGNED,
                    Set.of(
                            new TransitionEdge(PhaseStatus.ACTIVE, "start"),
                            new TransitionEdge(PhaseStatus.ASSIGNED, "re-assign")),
                    PhaseStatus.ACTIVE,
                    Set.of(
                            new TransitionEdge(PhaseStatus.COMPLETED, "complete"),
                            new TransitionEdge(PhaseStatus.COMPLETED, "force-complete")));

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final MatchLockdownService matchLockdownService;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * Jackson ObjectMapper for parsing {@code tournament.draftJson} in the Clause F guard
     * (DEC-59 Clause F, E51S18). Spring auto-wires the single {@code ObjectMapper} primary bean.
     */
    private final ObjectMapper objectMapper;

    public DefaultPhaseLifecycleService(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            MatchLockdownService matchLockdownService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.matchLockdownService = matchLockdownService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // transition() — single entry point for all status mutations (E51S05)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>AC-IMPL-TRANSITION-METHOD-API (E51S05). Validates {@code (source, target, verb)} against
     * {@link #ALLOWED} before any mutation. Acquires the per-tournament row-lock (DEC-37 Clause B)
     * as the first read after the null/not-found guards.
     *
     * <p>AC-IMPL-DEC-37-LOCK-PRESERVED: {@link TournamentRepository#findByIdForUpdate(UUID)} is
     * called as the first read inside the transaction.
     *
     * <p>AC-IMPL-ACTIVATION-GUARD-INSIDE-START: for the {@code ASSIGNED → ACTIVE "start"}
     * transition, the guard {@code !tournament.optimize OR phase.optimized} is evaluated before the
     * status mutation.
     */
    @Override
    @Transactional
    public Phase transition(UUID phaseId, PhaseStatus target, String verb) {
        // AC-ERROR-HANDLING-NULL-VERB-REJECTED: reject null verb before any DB access
        if (verb == null) {
            throw new IllegalArgumentException(
                    "verb must not be null — provide a transition verb (E51S05,"
                            + " AC-ERROR-HANDLING-NULL-VERB-REJECTED)");
        }

        // AC-ERROR-HANDLING-PHASE-NOT-FOUND: fail fast on unknown phaseId before lock + table
        Phase phase = requirePhase(phaseId);

        // DEC-37 Clause B: acquire per-tournament row-lock as the first read in the TX.
        // Re-read the phase under the lock to get the authoritative committed status.
        Tournament tournament = tournamentRepository.findByIdForUpdate(phase.getTournamentId());
        phase = requirePhase(phaseId); // fresh read under the lock

        PhaseStatus source;
        try {
            source = PhaseStatus.valueOf(phase.getStatus());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Phase "
                            + phaseId
                            + " has unrecognised status '"
                            + phase.getStatus()
                            + "' — cannot resolve transition (E51S05)",
                    e);
        }

        // Validate (source, target, verb) against ALLOWED transition table
        Set<TransitionEdge> outbound = ALLOWED.getOrDefault(source, Set.of());
        boolean edgeAllowed =
                outbound.stream().anyMatch(e -> e.target() == target && e.verb().equals(verb));
        if (!edgeAllowed) {
            throw new IllegalStateException(
                    "Phase "
                            + phaseId
                            + ": transition ("
                            + source.name()
                            + " → "
                            + target.name()
                            + " '"
                            + verb
                            + "') is not in the allowed transition table (DEC-55 D-4, E51S05,"
                            + " AC-TEST-TRANSITION-TABLE-DISALLOWED-REJECTED-RED)");
        }

        // AC-IMPL-ACTIVATION-GUARD-INSIDE-START (E51S05 + E51S18 DEC-59 Clause F):
        // Guard: !tournament.optimize OR phase.optimized OR section.gameMode == "siegerehrung"
        // DEC-59 Clause F amends DEC-55 D-6: siegerehrung phases are exempt from the optimize-guard
        // because slot-optimization is N/A for ceremony-ordering (no slot structure to optimize).
        if (target == PhaseStatus.ACTIVE && "start".equals(verb)) {
            boolean optimizeEnabled = tournament != null && tournament.isOptimize();
            boolean phaseOptimized = phase.isOptimized();
            boolean isSiegerehrung = isSiegerehrungPhase(tournament, phase);
            if (optimizeEnabled && !phaseOptimized && !isSiegerehrung) {
                throw new ConflictException(
                        "Phase "
                                + phaseId
                                + " cannot transition to ACTIVE: tournament "
                                + phase.getTournamentId()
                                + " has optimize=true and this phase has optimized=false;"
                                + " wait for slot-opt completion or cancel the slot-opt"
                                + " to apply Best-So-Far"
                                + " (DEC-55 D-6 + DEC-59 Clause F, E51S05,"
                                + " AC-IMPL-ACTIVATION-GUARD-INSIDE-START)");
            }
        }

        String previous = phase.getStatus();
        phase.setStatus(target.name());
        Phase saved = phaseRepository.save(phase);

        eventPublisher.publishEvent(
                new PhaseStatusChangedEvent(
                        this,
                        null, // tenantId — resolved by DomainEventBridge via TenantContext
                        phase.getTournamentId(),
                        phaseId,
                        previous,
                        target.name()));

        log.debug(
                "[E51S05] Phase {} transitioned {} → {} (verb={})",
                phaseId,
                previous,
                target,
                verb);
        return saved;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). If the phase is already {@code PREPARED}, returns idempotently
     * without publishing an event. If status is any other state (ACTIVE, COMPLETED, ASSIGNED),
     * throws {@link ConflictException} — mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     *
     * <p>E51S06 rollback of E48S21: pure status flip only. Avatar persistence and match generation
     * are handled by separate pipeline steps (E51S02 + E51S03). This method does NOT call {@code
     * commitTransition}.
     *
     * <p>AC-IMPL-PHASE-LIFECYCLE-PREPARE (E48S17 / E51S06).
     */
    @Override
    @Transactional
    public Phase prepare(UUID phaseId) {
        // Initial phase read to get the tournamentId (needed for the lock)
        Phase phase = requirePhase(phaseId);

        // DEC-37 Clause B: acquire per-tournament row-lock BEFORE reading mutable state.
        tournamentRepository.findByIdForUpdate(phase.getTournamentId());
        phase = requirePhase(phaseId); // fresh read under the lock

        // Idempotent: already PREPARED — return without transition or event
        if ("PREPARED".equals(phase.getStatus())) {
            log.debug("[E48S17] Phase {} already PREPARED — idempotent return", phaseId);
            return phase;
        }

        if (!"PENDING".equals(phase.getStatus())) {
            throw new ConflictException(
                    "Cannot prepare phase: current status is "
                            + phase.getStatus()
                            + " (expected PENDING or PREPARED). Phase id="
                            + phaseId);
        }

        String previous = phase.getStatus();
        phase.setStatus("PREPARED");
        Phase saved = phaseRepository.save(phase);

        eventPublisher.publishEvent(
                new PhaseStatusChangedEvent(
                        this,
                        null, // tenantId — resolved by DomainEventBridge via TenantContext
                        phase.getTournamentId(),
                        phaseId,
                        previous,
                        "PREPARED"));

        log.debug("[E48S17] Phase {} transitioned {} → PREPARED", phaseId, previous);
        return saved;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). If the current status is not {@code ASSIGNED}, throws {@link
     * ConflictException} — mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     *
     * <p>Predecessor check (E48S17): if sequenceNumber &gt; 1, the predecessor phase
     * (sequenceNumber - 1) must be {@code COMPLETED}. HTTP 409 with operator-actionable message if
     * not.
     *
     * <p>E51S06 refactor: {@code start()} now requires {@code ASSIGNED} status (not PREPARED).
     * After E51S06, {@code commitTransition} flips the phase to ASSIGNED (via the PREPARED→ASSIGNED
     * "assign" verb transition). This replaces the E48S17 PREPARED check.
     *
     * <p>AC-IMPL-PHASE-LIFECYCLE-START-REFACTOR (E48S17 / E51S06).
     */
    @Override
    @Transactional
    public Phase start(UUID phaseId) {
        // Initial phase read to get the tournamentId (needed for the lock)
        Phase phase = requirePhase(phaseId);

        // DEC-37 Clause B: acquire per-tournament row-lock BEFORE reading mutable state.
        // Re-read the phase AFTER acquiring the lock so concurrent threads see the current
        // committed status — this is the serialisation point.
        tournamentRepository.findByIdForUpdate(phase.getTournamentId());
        phase = requirePhase(phaseId); // fresh read under the lock

        // E51S06: start() now requires ASSIGNED (commitTransition flips PREPARED → ASSIGNED)
        if (!"ASSIGNED".equals(phase.getStatus())) {
            throw new ConflictException(
                    "Cannot start phase: current status is "
                            + phase.getStatus()
                            + " (expected ASSIGNED). Use commitTransition first. Phase id="
                            + phaseId);
        }

        // E48S17: predecessor check — predecessor must be COMPLETED (or absent for seq=1)
        int sequenceNumber = phase.getSequenceNumber();
        if (sequenceNumber > 1) {
            Optional<Phase> predecessorOpt =
                    phaseRepository.findByTournamentIdAndSequenceNumber(
                            phase.getTournamentId(), sequenceNumber - 1);
            if (predecessorOpt.isPresent()) {
                Phase predecessor = predecessorOpt.get();
                if (!"COMPLETED".equals(predecessor.getStatus())) {
                    throw new ConflictException(
                            "Cannot start phase "
                                    + sequenceNumber
                                    + ": predecessor phase "
                                    + predecessor.getSequenceNumber()
                                    + " is "
                                    + predecessor.getStatus()
                                    + ", expected COMPLETED. Phase id="
                                    + phaseId);
                }
            }
        }

        String previous = phase.getStatus();
        phase.setStatus("ACTIVE");
        Phase saved = phaseRepository.save(phase);

        eventPublisher.publishEvent(
                new PhaseStatusChangedEvent(
                        this,
                        null, // tenantId — resolved by DomainEventBridge via TenantContext
                        phase.getTournamentId(),
                        phaseId,
                        previous,
                        "ACTIVE"));

        log.debug("[E48S17] Phase {} transitioned {} → ACTIVE", phaseId, previous);
        return saved;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). Verifies all matches are in terminal states before transitioning. If
     * unfinished matches exist, throws {@link ConflictException} with an operator-actionable
     * message.
     */
    @Override
    @Transactional
    public Phase complete(UUID phaseId) {
        Phase phase = requirePhase(phaseId);

        // DEC-37 Clause B: acquire per-tournament row-lock, then re-read phase for fresh status
        tournamentRepository.findByIdForUpdate(phase.getTournamentId());
        phase = requirePhase(phaseId);

        if (!"ACTIVE".equals(phase.getStatus())) {
            throw new ConflictException(
                    "Cannot complete phase: current status is "
                            + phase.getStatus()
                            + " (expected ACTIVE). Phase id="
                            + phaseId);
        }

        long unfinished = matchRepository.countUnfinishedByPhaseId(phaseId);
        if (unfinished > 0) {
            throw new ConflictException(
                    "Cannot complete phase: "
                            + unfinished
                            + " unfinished match(es) remain in phase "
                            + phaseId
                            + ". Use force-complete (Notabschluss) to override.");
        }

        String previous = phase.getStatus();
        phase.setStatus("COMPLETED");
        Phase saved = phaseRepository.save(phase);

        eventPublisher.publishEvent(
                new PhaseStatusChangedEvent(
                        this, null, phase.getTournamentId(), phaseId, previous, "COMPLETED"));

        log.debug("[E48S06] Phase {} transitioned {} → COMPLETED", phaseId, previous);
        return saved;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). After status update, delegates to {@link
     * MatchLockdownService#cancelOpenMatchesByTournamentId(UUID)} to bulk-cancel all unfinished
     * matches in the tournament — same transaction, same lock
     * (AC-IMPL-FORCE-COMPLETE-REUSES-LOCKDOWN).
     */
    @Override
    @Transactional
    public Phase forceComplete(UUID phaseId) {
        Phase phase = requirePhase(phaseId);

        // DEC-37 Clause B: acquire per-tournament row-lock, then re-read phase for fresh status
        tournamentRepository.findByIdForUpdate(phase.getTournamentId());
        phase = requirePhase(phaseId);

        if (!"ACTIVE".equals(phase.getStatus())) {
            throw new ConflictException(
                    "Cannot force-complete phase: current status is "
                            + phase.getStatus()
                            + " (expected ACTIVE). Phase id="
                            + phaseId);
        }

        String previous = phase.getStatus();
        phase.setStatus("COMPLETED");
        Phase saved = phaseRepository.save(phase);

        // AC-IMPL-FORCE-COMPLETE-REUSES-LOCKDOWN: reuse E48S04 logic to cancel unfinished matches
        // within the same @Transactional boundary (DEC-37 Clause B lock already held).
        matchLockdownService.cancelOpenMatchesByTournamentId(phase.getTournamentId());

        eventPublisher.publishEvent(
                new PhaseStatusChangedEvent(
                        this, null, phase.getTournamentId(), phaseId, previous, "COMPLETED"));

        log.debug(
                "[E48S06] Phase {} force-completed (Notabschluss): {} → COMPLETED",
                phaseId,
                previous);
        return saved;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Phase requirePhase(UUID phaseId) {
        return phaseRepository
                .findById(phaseId)
                .orElseThrow(() -> new IllegalArgumentException("Phase not found: " + phaseId));
    }

    /**
     * Determines whether the given phase corresponds to a {@code siegerehrung} section in the
     * tournament's draft configuration (DEC-59 Clause F, E51S18).
     *
     * <p>Parses {@code tournament.draftJson} via Jackson to find the {@link DraftSection} at index
     * {@code phase.sequenceNumber - 1} (sequenceNumber is 1-based; sections list is 0-based).
     * Returns {@code true} if the matching section has {@code gameMode == "siegerehrung"}.
     *
     * <p>Fail-safe: returns {@code false} (guard fires normally) if:
     *
     * <ul>
     *   <li>tournament is null or has no draftJson
     *   <li>draftJson cannot be parsed (malformed JSON — operator data error)
     *   <li>sequenceNumber is out of range for the sections list
     * </ul>
     *
     * @param tournament the locked tournament aggregate (may be null if not found)
     * @param phase the phase being evaluated
     * @return {@code true} if the phase's section has gameMode "siegerehrung"; {@code false}
     *     otherwise
     * @see <a href="DEC-59">DEC-59 Clause F — activation-guard gameMode OR-term</a>
     * @see <a href="E51S18">E51S18 — operationalize DEC-59 Clause F</a>
     */
    private boolean isSiegerehrungPhase(Tournament tournament, Phase phase) {
        if (tournament == null || tournament.getDraftJson() == null) {
            return false;
        }
        try {
            DraftConfig config = objectMapper.readValue(tournament.getDraftJson(), DraftConfig.class);
            List<DraftSection> sections = config.getSections();
            int index = phase.getSequenceNumber() - 1; // sequenceNumber is 1-based
            if (index < 0 || index >= sections.size()) {
                return false;
            }
            return "siegerehrung".equals(sections.get(index).getGameMode());
        } catch (Exception e) {
            log.warn(
                    "[E51S18] isSiegerehrungPhase: failed to parse draftJson for tournament {}"
                            + " — defaulting to false (guard fires normally). Error: {}",
                    tournament.getId(),
                    e.getMessage());
            return false;
        }
    }
}
