package de.vvwt.dispatcher.job;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link JobRecord}.
 *
 * <p>See Story E01S06 AC10 (save) and E01S07 AC1/AC5 (decomposition/distribution queries).
 */
public interface JobRepository extends JpaRepository<JobRecord, UUID> {

    /**
     * Finds the oldest queued job for the decomposer to claim.
     *
     * <p>Used by {@code PacketDecomposerService} to pick the next job to decompose (E01S07 AC1).
     *
     * @param status job status — typically {@code "queued"}
     * @return the oldest matching job, or empty if none
     */
    Optional<JobRecord> findFirstByStatusOrderBySubmittedAtAsc(String status);

    /**
     * Atomically claims a job for decomposition by transitioning it from {@code "queued"} to {@code
     * "decomposing"}.
     *
     * <p>Returns the number of rows updated (1 = claimed successfully, 0 = already claimed). The
     * conditional {@code WHERE status = 'queued'} prevents double-claiming under concurrency.
     *
     * @param jobId the job to claim
     * @return 1 if the claim succeeded, 0 if the job was already claimed or does not exist
     */
    @Modifying
    @Query(
            "UPDATE JobRecord j SET j.status = 'decomposing' WHERE j.jobId = :jobId AND j.status ="
                    + " 'queued'")
    int claimForDecomposition(UUID jobId);
}
