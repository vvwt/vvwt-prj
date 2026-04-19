package de.vvwt.dispatcher.result;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link ResultAuditEntry}.
 *
 * <p>See Story E01S08 AC8.
 */
public interface ResultAuditRepository extends JpaRepository<ResultAuditEntry, Long> {}
