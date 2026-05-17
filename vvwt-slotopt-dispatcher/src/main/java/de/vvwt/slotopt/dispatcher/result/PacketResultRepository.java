// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC repository for {@link PacketResult} entities.
 *
 * <p>Per DEC-35: Spring Data repository interfaces ARE the port — they do not require a separate
 * {@code Default*} implementation wrapper. The interface itself is the public first-party surface.
 *
 * <p>DEC-46 (DEC-26 amendment): DAO integration tests for this repository follow the three-rule
 * generator/evaluator-separation pattern via {@link
 * de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport}.
 *
 * <p>Story: E60S02; AC-GOV-QUERYABLE-SUBSTRATE; AC-TEST-RETAINED-RESULTS-QUERYABLE-BY-JOB; DEC-26,
 * DEC-35, DEC-46
 */
public interface PacketResultRepository extends CrudRepository<PacketResult, Long> {

    /**
     * Returns all retained packet results for the given job.
     *
     * <p>This is the primary access path for E60S03 cross-packet aggregation — all retained results
     * for one job must be retrievable as a set.
     *
     * @param jobId the UUID of the owning job
     * @return all {@link PacketResult} records for the job; empty list if none retained
     */
    List<PacketResult> findAllByJobId(UUID jobId);
}
