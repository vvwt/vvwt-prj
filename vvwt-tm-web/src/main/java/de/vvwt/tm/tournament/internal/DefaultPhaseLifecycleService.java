package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchLockdownService;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link PhaseLifecycleService} (DEC-35, E48S06, E48S17).
 *
 * <p>Drives each phase through its lifecycle status transitions. Every method acquires a
 * per-tournament pessimistic DB row-lock via {@link TournamentRepository#findByIdForUpdate(UUID)}
 * as its FIRST READ — serialising concurrent transitions on the same tournament aggregate root per
 * DEC-37 Clause B.
 *
 * <p>{@link #prepare(UUID)} transitions PENDING → PREPARED. Idempotent on PREPARED status.
 * Re-entrant-lock note: prepare() acquires the DEC-37 lock; if it later delegates to
 * commitTransition() in the same {@code @Transactional} scope, H2 + PostgreSQL treat SELECT FOR
 * UPDATE on an already-held row as a no-op within the same transaction (semantically correct).
 *
 * <p>{@link #start(UUID)} requires {@code PREPARED} status (E48S17 refactor from {@code PENDING}).
 * Additionally checks predecessor completion: if sequenceNumber &gt; 1, the predecessor phase
 * (sequenceNumber - 1) must be {@code COMPLETED}.
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
 * @see <a href="E48S06">E48S06 — Phase-Lifecycle Service</a>
 * @see <a href="E48S17">E48S17 — PREPARED enum + prepare() + start() refactor</a>
 */
@Service("tmPhaseLifecycleService")
public class DefaultPhaseLifecycleService implements PhaseLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPhaseLifecycleService.class);

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final MatchLockdownService matchLockdownService;
    private final ApplicationEventPublisher eventPublisher;

    public DefaultPhaseLifecycleService(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            MatchLockdownService matchLockdownService,
            ApplicationEventPublisher eventPublisher) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.matchLockdownService = matchLockdownService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). If the phase is already {@code PREPARED}, returns idempotently
     * without publishing an event. If status is any other state (ACTIVE, COMPLETED), throws {@link
     * ConflictException} — mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     *
     * <p>AC-IMPL-PHASE-LIFECYCLE-PREPARE (E48S17).
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
     * read (DEC-37 Clause B). If the current status is not {@code PREPARED}, throws {@link
     * ConflictException} — mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     *
     * <p>Predecessor check (E48S17): if sequenceNumber &gt; 1, the predecessor phase
     * (sequenceNumber - 1) must be {@code COMPLETED}. HTTP 409 with operator-actionable message if
     * not.
     *
     * <p>AC-IMPL-PHASE-LIFECYCLE-START-REFACTOR (E48S17).
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

        // E48S17: start() now requires PREPARED (not PENDING)
        if (!"PREPARED".equals(phase.getStatus())) {
            throw new ConflictException(
                    "Cannot start phase: current status is "
                            + phase.getStatus()
                            + " (expected PREPARED). Use prepare() first. Phase id="
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
}
