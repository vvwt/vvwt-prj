package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentLifecycleService;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link TournamentLifecycleService} (DEC-35, E48S03).
 *
 * <p>Drives the tournament through its lifecycle status transitions. Every method acquires a
 * per-tournament pessimistic DB row-lock via {@link TournamentRepository#findByIdForUpdate(UUID)}
 * as its FIRST READ — serialising concurrent transitions on the same tournament aggregate root per
 * DEC-37 Clause B.
 *
 * <p>Match-Cancel-Lockdown (bulk-cancelling open matches when a tournament is cancelled) is NOT in
 * scope for this service — that belongs to E48S04. The {@code cancel()} method only updates {@code
 * tournament.status} per AC-NO-MATCH-CANCEL-IN-THIS-STORY.
 *
 * @see TournamentLifecycleService
 * @see <a href="DEC-35">DEC-35 — package layout: impl in .internal</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="E48S03">E48S03 — Tournament lifecycle transitions</a>
 */
@Service
public class DefaultTournamentLifecycleService implements TournamentLifecycleService {

    private static final Set<String> CANCELLABLE_STATUSES = Set.of("PLANNED", "ACTIVE");

    private final TournamentRepository tournamentRepository;

    public DefaultTournamentLifecycleService(TournamentRepository tournamentRepository) {
        this.tournamentRepository = tournamentRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). If the current status is not {@code DRAFT}, throws {@link
     * ConflictException} — mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     */
    @Override
    @Transactional
    public Tournament markPlanned(UUID tournamentId) {
        // DEC-37 Clause B: acquire per-tournament row-lock as the FIRST read
        Tournament t = tournamentRepository.findByIdForUpdate(tournamentId);

        if (!"DRAFT".equals(t.getStatus())) {
            throw new ConflictException(
                    "Cannot mark tournament as PLANNED: current status is "
                            + t.getStatus()
                            + " (expected DRAFT). Tournament id="
                            + tournamentId);
        }

        t.setStatus("PLANNED");
        return tournamentRepository.save(t);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). If the current status is not {@code PLANNED}, throws {@link
     * ConflictException} — mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     */
    @Override
    @Transactional
    public Tournament activate(UUID tournamentId) {
        // DEC-37 Clause B: acquire per-tournament row-lock as the FIRST read
        Tournament t = tournamentRepository.findByIdForUpdate(tournamentId);

        if (!"PLANNED".equals(t.getStatus())) {
            throw new ConflictException(
                    "Cannot activate tournament: current status is "
                            + t.getStatus()
                            + " (expected PLANNED). Tournament id="
                            + tournamentId);
        }

        t.setStatus("ACTIVE");
        return tournamentRepository.save(t);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). If the current status is not {@code ACTIVE}, throws {@link
     * ConflictException} — mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     */
    @Override
    @Transactional
    public Tournament complete(UUID tournamentId) {
        // DEC-37 Clause B: acquire per-tournament row-lock as the FIRST read
        Tournament t = tournamentRepository.findByIdForUpdate(tournamentId);

        if (!"ACTIVE".equals(t.getStatus())) {
            throw new ConflictException(
                    "Cannot complete tournament: current status is "
                            + t.getStatus()
                            + " (expected ACTIVE). Tournament id="
                            + tournamentId);
        }

        t.setStatus("COMPLETED");
        return tournamentRepository.save(t);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Lock is acquired via {@link TournamentRepository#findByIdForUpdate(UUID)} as the first
     * read (DEC-37 Clause B). If the current status is not {@code PLANNED} or {@code ACTIVE},
     * throws {@link ConflictException} — mapped to HTTP 409 by {@code GlobalExceptionHandler}.
     *
     * <p>This method only updates {@code tournament.status} — it does NOT cancel matches.
     * Match-Cancel-Lockdown belongs to E48S04 per AC-NO-MATCH-CANCEL-IN-THIS-STORY.
     */
    @Override
    @Transactional
    public Tournament cancel(UUID tournamentId) {
        // DEC-37 Clause B: acquire per-tournament row-lock as the FIRST read
        Tournament t = tournamentRepository.findByIdForUpdate(tournamentId);

        if (!CANCELLABLE_STATUSES.contains(t.getStatus())) {
            throw new ConflictException(
                    "Cannot cancel tournament: current status is "
                            + t.getStatus()
                            + " (expected PLANNED or ACTIVE). Tournament id="
                            + tournamentId);
        }

        // AC-NO-MATCH-CANCEL-IN-THIS-STORY: only update tournament.status
        t.setStatus("CANCELLED");
        return tournamentRepository.save(t);
    }
}
