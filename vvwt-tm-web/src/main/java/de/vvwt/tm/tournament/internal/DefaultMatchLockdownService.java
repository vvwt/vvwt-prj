// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchLockdownService;
import de.vvwt.tm.tournament.MatchRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link MatchLockdownService} (DEC-35, E48S04).
 *
 * <p>Bulk-cancels all unfinished matches ({@code OPEN/ENABLED/INPROGRESS/ONCHECK → CANCELED(-10)})
 * for a tournament. Must be called from within an existing {@code @Transactional} boundary where
 * the per-tournament pessimistic DB row-lock has already been acquired (DEC-37 Clause B). This
 * service does NOT acquire its own lock.
 *
 * <p>No {@code @Transactional} annotation on this class: callers are responsible for the
 * transaction boundary (AC-IMPL-MATCH-BULK-CANCEL — same boundary as tournament status change).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35 — implementation in {@code de.vvwt.tm.tournament.internal}; interface {@link
 *       MatchLockdownService} in {@code de.vvwt.tm.tournament} (public package); named {@code
 *       Default*Service} per naming canon
 *   <li>DEC-37 Clause B — caller holds the per-tournament DB row-lock; this service does NOT
 *       acquire a lock directly (AC-IMPL-MATCH-BULK-CANCEL)
 *   <li>DEC-22 — all AC-TEST-* tests were authored RED-first before this implementation
 *   <li>AC-GOVERNANCE-NO-NEW-ENUM-VALUE — uses existing {@link
 *       de.vvwt.tm.tournament.MatchState#CANCELED}
 *   <li>AC-GOVERNANCE-NO-SCHEMA-CHANGE — bulk-update is pure DML; no DDL
 * </ul>
 *
 * @see MatchLockdownService
 * @see <a href="DEC-35">DEC-35 — package layout: impl in .internal</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock (caller holds)</a>
 * @see <a href="E48S04">E48S04 — Match-Cancel-Lockdown</a>
 */
@Service
public class DefaultMatchLockdownService implements MatchLockdownService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMatchLockdownService.class);

    private final MatchRepository matchRepository;

    public DefaultMatchLockdownService(MatchRepository matchRepository) {
        this.matchRepository = matchRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link MatchRepository#bulkCancelByTournamentId(UUID)} which executes an
     * UPDATE with {@code WHERE state IN (0, 10, 30, 35)} — only unfinished matches are affected.
     * Terminal matches ({@code FINISHED_*}, already-{@code CANCELED}) are NOT touched (idempotent
     * by WHERE-clause construction, AC-IMPL-MATCH-CANCEL-IDEMPOTENT).
     */
    @Override
    public void cancelOpenMatchesByTournamentId(UUID tournamentId) {
        int count = matchRepository.bulkCancelByTournamentId(tournamentId);
        log.debug(
                "[E48S04] Bulk-cancelled {} unfinished match(es) for tournament={}",
                count,
                tournamentId);
    }
}
