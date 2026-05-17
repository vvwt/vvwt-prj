// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link JobRecord}.
 *
 * <p>Per DEC-35: Spring Data {@code CrudRepository} interfaces ARE the port by definition — no
 * separate public interface wrapper is needed or allowed (Spring-Data carve-out).
 *
 * <p>Custom finder: {@link #findByJobId(UUID)} for job status lookup by external UUID.
 *
 * <p>Story: E37S07; AC-JOB-REPOSITORY; DEC-35
 */
public interface JobRepository extends CrudRepository<JobRecord, Long> {

    /**
     * Finds a {@link JobRecord} by its externally-visible job UUID.
     *
     * @param jobId the external job UUID
     * @return the job record if found, otherwise empty
     */
    Optional<JobRecord> findByJobId(UUID jobId);
}
