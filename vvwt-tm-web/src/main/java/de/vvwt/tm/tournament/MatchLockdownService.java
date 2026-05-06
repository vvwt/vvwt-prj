package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Public port for match-cancel-lockdown operations in the {@code tournament} Modulith context
 * (DEC-35, E48S04).
 *
 * <p>Provides bulk-cancel of all unfinished matches in a tournament. Called from within the same
 * {@code @Transactional} boundary as the tournament status transition — the per-tournament
 * pessimistic DB row-lock (DEC-37 Clause B) is already held by the caller.
 *
 * <p>The canonical implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultMatchLockdownService} in the {@code tournament.internal}
 * package per DEC-35.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultMatchLockdownService
 * @see MatchState#CANCELED
 * @see <a href="DEC-35">DEC-35 — package layout: interface in public package</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock (held by
 *     caller)</a>
 * @see <a href="E48S04">E48S04 — Match-Cancel-Lockdown</a>
 */
public interface MatchLockdownService {

    /**
     * Bulk-cancels all unfinished matches belonging to the given tournament.
     *
     * <p>Matches with {@code state IN (OPEN=0, ENABLED=10, INPROGRESS=30, ONCHECK=35)} are updated
     * to {@code state = CANCELED(-10)}. Matches already in a terminal state ({@code
     * FINISHED_WINNER1}, {@code FINISHED_WINNER2}, {@code FINISHED_STANDOFF}, {@code CANCELED}) are
     * NOT modified — this ensures audit preservation of finished match results
     * (AC-TEST-FINISHED-MATCH-AUDIT-PRESERVED-RED).
     *
     * <p>This operation is idempotent: if all unfinished matches are already {@code CANCELED}, the
     * update affects 0 rows.
     *
     * <p>The caller MUST already hold the per-tournament pessimistic DB row-lock via {@link
     * TournamentRepository#findByIdForUpdate(UUID)} before invoking this method (DEC-37 Clause B).
     * This method does NOT acquire its own lock.
     *
     * @param tournamentId the tournament whose unfinished matches should be cancelled (NOT NULL)
     */
    void cancelOpenMatchesByTournamentId(UUID tournamentId);
}
