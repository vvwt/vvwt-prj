package de.vvwt.dispatcher.result;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link LateResult}.
 *
 * <p>See Story E01S08 AC4.
 */
public interface LateResultRepository extends JpaRepository<LateResult, Long> {}
