package de.vvwt.dispatcher.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Spring Data JPA repository for {@link JobRecord}.
 *
 * <p>E01S06 only saves new records. Query methods for packet decomposition
 * and distribution are added in E01S07.
 *
 * <p>See Story E01S06 AC10.
 */
public interface JobRepository extends JpaRepository<JobRecord, UUID> {
    // E01S07 will add query methods here
}
