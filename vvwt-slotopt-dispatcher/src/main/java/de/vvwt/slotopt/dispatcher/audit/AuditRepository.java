package de.vvwt.slotopt.dispatcher.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data repository for {@link AuditEntry}.
 *
 * <p>Per DEC-35: the Spring Data {@code CrudRepository} interface IS the port — the contract maps
 * cleanly to Spring Data CRUD semantics, so no hand-authored wrapper is needed. The interface lives
 * in the public {@code audit} package per DEC-35.
 *
 * <p>All consumers (services, tests) reference this interface, never the Spring Data
 * generated-proxy directly (DEC-36).
 *
 * <p>Story: E37S06; AC-AUDIT-REPOSITORY
 */
public interface AuditRepository extends CrudRepository<AuditEntry, Long> {

    /**
     * Finds all audit entries for a given worker ID, ordered by occurrence timestamp descending.
     *
     * @param workerId the worker UUID to query for; must not be {@code null}
     * @return list of matching entries, empty if none found
     */
    List<AuditEntry> findByWorkerIdOrderByOccurredAtDesc(UUID workerId);

    /**
     * Finds all audit entries for a given event type, ordered by occurrence timestamp descending.
     *
     * @param eventType the event type string to query for (e.g., {@code "KEY_REGISTERED"})
     * @return list of matching entries, empty if none found
     */
    List<AuditEntry> findByEventTypeOrderByOccurredAtDesc(String eventType);
}
