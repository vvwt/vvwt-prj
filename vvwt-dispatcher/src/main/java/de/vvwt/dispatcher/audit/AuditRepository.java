package de.vvwt.dispatcher.audit;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AuditEntry}.
 *
 * <p>Save-only usage: every request results in a new audit row. No read queries
 * are needed by the dispatcher itself (read access is reserved for admin tooling).
 *
 * <p>See Story E01S06 AC12.
 */
public interface AuditRepository extends JpaRepository<AuditEntry, Long> {
    // No custom query methods needed for E01S06
}
