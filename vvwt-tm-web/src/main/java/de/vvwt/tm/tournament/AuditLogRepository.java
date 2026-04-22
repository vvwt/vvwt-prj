package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public port for tenant-scoped, append-only {@link AuditLogEntry} persistence (DEC-35, E31S01).
 *
 * <p>Hand-authored interface port per DEC-35 §2. Cross-context consumers (e.g., {@code
 * de.vvwt.tm.domain.CascadeRecomputeService}) reference this interface instead of the former {@code
 * tournament.internal.AuditLogRepository} concrete class, eliminating the forbidden {@code
 * .internal} import per DEC-35.
 *
 * <p>The canonical implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultAuditLogRepository}.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultAuditLogRepository
 * @see AuditLogEntry
 * @see <a href="DEC-35">DEC-35 — package layout: interfaces in public package</a>
 * @see <a href="E31S01">E31S01 — interface extraction (MANDATORY per
 *     AC-INTERFACE-EXTRACT-MANDATORY)</a>
 */
public interface AuditLogRepository {

    /**
     * Appends a new {@link AuditLogEntry} row. Tenant scoping is enforced.
     *
     * @param entry the entry to append (id must be set by caller)
     * @return the saved entry
     * @throws IllegalStateException if no tenant context is active
     */
    AuditLogEntry save(AuditLogEntry entry);

    /**
     * Returns the audit log entry for the given id, scoped to the current tenant.
     *
     * @param id the entry UUID
     * @return Optional.of(entry) if found, Optional.empty() otherwise
     * @throws IllegalStateException if no tenant context is active
     */
    Optional<AuditLogEntry> findById(UUID id);

    /**
     * Returns audit entries for a given match and set index in chronological order, scoped to the
     * active tenant.
     *
     * @param matchId the match whose audit entries to retrieve
     * @param setIndex the set index within the match
     * @return list of audit entries in chronological order; never null
     * @throws IllegalStateException if no tenant context is active
     */
    List<AuditLogEntry> findByMatchIdAndSetIndexOrderByChangedAt(UUID matchId, int setIndex);

    /**
     * Deleting audit log entries is FORBIDDEN (append-only invariant).
     *
     * @param id the ID (ignored)
     * @throws UnsupportedOperationException always — audit log is append-only per DEC-22/E21S05
     */
    void deleteById(UUID id);
}
