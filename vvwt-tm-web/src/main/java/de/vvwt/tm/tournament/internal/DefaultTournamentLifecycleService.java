package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchLockdownService;
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
 * <p>Match-Cancel-Lockdown (bulk-cancelling open matches when a tournament is cancelled) is
 * implemented in this service via {@link MatchLockdownService} injection. The {@code cancel()}
 * method delegates to {@link MatchLockdownService#cancelOpenMatchesByTournamentId(UUID)} after the
 * tournament status transition, within the same {@code @Transactional} boundary and under the same
 * per-tournament DB row-lock (DEC-37 Clause B, E48S04).
 *
 * @see TournamentLifecycleService
 * @see MatchLockdownService
 * @see <a href="DEC-35">DEC-35 — package layout: impl in .internal</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="E48S03">E48S03 — Tournament lifecycle transitions</a>
 * @see <a href="E48S04">E48S04 — Match-Cancel-Lockdown</a>
 */
@Service
public class DefaultTournamentLifecycleService implements TournamentLifecycleService {

    private static final Set<String> CANCELLABLE_STATUSES = Set.of("PLANNED", "ACTIVE");

    private final TournamentRepository tournamentRepository;
    private final MatchLockdownService matchLockdownService;

    public DefaultTournamentLifecycleService(
            TournamentRepository tournamentRepository, MatchLockdownService matchLockdownService) {
        this.tournamentRepository = tournamentRepository;
        this.matchLockdownService = matchLockdownService;
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
     * <p>After setting {@code tournament.status = CANCELLED}, delegates to {@link
     * MatchLockdownService#cancelOpenMatchesByTournamentId(UUID)} to bulk-cancel all unfinished
     * matches within the SAME transaction and under the SAME per-tournament DB row-lock (DEC-37
     * Clause B, E48S04, AC-IMPL-MATCH-BULK-CANCEL).
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

        t.setStatus("CANCELLED");
        Tournament saved = tournamentRepository.save(t);

        // E48S04 AC-IMPL-MATCH-BULK-CANCEL: bulk-cancel all unfinished matches
        // within the same @Transactional boundary (DEC-37 Clause B lock is already held).
        matchLockdownService.cancelOpenMatchesByTournamentId(tournamentId);

        return saved;
    }
}
