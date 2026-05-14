package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public port for tenant-scoped, append-only {@link AuditLogEntry} persistence (DEC-35, E31S01).
 *
 * <p>Hand-authored interface port per DEC-35 §2. Cross-context consumers reference this interface
 * instead of any concrete implementation, eliminating forbidden {@code .internal} imports per
 * DEC-35.
 *
 * <p>Post-E55S13: the canonical implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultAuditLogRepository} — a file-based, JSONL append-only
 * implementation backed by {@code java.nio.channels.FileChannel} (per-tournament physical
 * separation, DEC-14 2026-05-14 amendment).
 *
 * <p>API note: all query methods accept {@code tournamentId} as the first parameter (DDD
 * aggregate-id-first). The implementation routes to the correct per-tournament JSONL file using
 * this parameter.
 *
 * <p>Append-only invariant: {@code deleteById} has been removed from this interface entirely (per
 * AC-IMPL-READ-API-EXTENDED Brief O-9 strict append-only). Any caller that previously held a
 * reference to {@code deleteById} MUST remove the call; there is no replacement.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultAuditLogRepository
 * @see AuditLogEntry
 * @see <a href="DEC-14">DEC-14 — V1 persistence; file-based audit_log carve-out (2026-05-14)</a>
 * @see <a href="DEC-35">DEC-35 — package layout: interfaces in public package</a>
 * @see <a href="E55S13">E55S13 — file-based audit log isolation (AC-IMPL-READ-API-EXTENDED)</a>
 */
public interface AuditLogRepository {

    /**
     * Appends a new {@link AuditLogEntry} to the tournament's audit log file.
     *
     * <p>Must be called from within an active Spring {@code @Transactional} context. The actual
     * file write is enqueued asynchronously after the surrounding transaction commits (via {@code
     * TransactionSynchronization.afterCommit()}). If the transaction rolls back, no audit row is
     * written.
     *
     * @param entry the entry to append (id and tournamentId must be set by caller)
     * @return the entry (unchanged)
     * @throws IllegalStateException if no active Spring transaction is present
     */
    AuditLogEntry save(AuditLogEntry entry);

    /**
     * Returns the audit log entry for the given tournament and entry id.
     *
     * @param tournamentId the tournament UUID (used to locate the per-tournament JSONL file)
     * @param id the entry UUID
     * @return Optional.of(entry) if found, Optional.empty() otherwise
     */
    Optional<AuditLogEntry> findByTournamentIdAndId(UUID tournamentId, UUID id);

    /**
     * Returns audit entries for a given tournament, match and set index in chronological order
     * (JSONL line order = append order = chronological order).
     *
     * @param tournamentId the tournament UUID (routes to per-tournament JSONL file)
     * @param matchId the match whose audit entries to retrieve
     * @param setIndex the set index within the match
     * @return list of audit entries in chronological order; never null; empty if none found
     */
    List<AuditLogEntry> findByTournamentIdAndMatchIdAndSetIndexOrderByChangedAt(
            UUID tournamentId, UUID matchId, int setIndex);
}
