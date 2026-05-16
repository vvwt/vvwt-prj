// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.audit.AuditService;
import de.vvwt.slotopt.dispatcher.cache.CachedResult;
import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import de.vvwt.slotopt.dispatcher.identity.testsupport.DispatcherDaoTestSupport;
import de.vvwt.slotopt.dispatcher.job.internal.DefaultJobService;
import de.vvwt.slotopt.dispatcher.packet.PacketDecomposerService;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.packet.internal.DefaultPacketDecomposerService;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test: after a non-cache-hit job is submitted, packets are persisted and the job
 * status advances to {@code DECOMPOSED}.
 *
 * <p>Applies DEC-26 / DEC-46 three-rule DAO governance:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway loads both {@code
 *       db/migration/job/V3__create_job_table.sql} and {@code
 *       db/migration/packet/V4__create_packet_table.sql}.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write assertions use assertj-db {@link
 *       Table} directly against the test DataSource, NOT the repository's read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> No fixtures needed (write-path test); the IT asserts
 *       the write path directly.
 * </ol>
 *
 * <p>RED-first per DEC-22 / AC-TEST-DECOMPOSE-INVOKED-ON-SUBMIT + AC-TEST-JOB-DECOMPOSED-TRANSITION
 * (E60S01).
 *
 * <p>Story: E60S01; AC-GOV-DAO-IT-THREE-RULE; DEC-26, DEC-46
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(DefaultPacketDecomposerService.class)
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-packet-persist-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations=classpath:db/migration/job,classpath:db/migration/packet",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PacketPersistenceAfterSubmitIT {

    @Autowired private JobRepository jobRepository;

    @Autowired private PacketRepository packetRepository;

    @Autowired private PacketDecomposerService packetDecomposerService;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    /** JobService wired with always-miss cache (standard non-cache-hit path). */
    private JobService jobService;

    @BeforeEach
    void setUp() {
        assertDb = DispatcherDaoTestSupport.assertDbOf(dataSource);

        AuditService noOpAudit = mock(AuditService.class);
        ResultsCacheService alwaysMissCache = mock(ResultsCacheService.class);
        when(alwaysMissCache.lookup(any(byte[].class), any())).thenReturn(Optional.empty());

        jobService =
                new DefaultJobService(
                        jobRepository,
                        noOpAudit,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        alwaysMissCache,
                        packetDecomposerService,
                        packetRepository);
    }

    /**
     * AC-TEST-DECOMPOSE-INVOKED-ON-SUBMIT + AC-TEST-JOB-DECOMPOSED-TRANSITION (E60S01):
     *
     * <p>After submit-job (non-cache-hit), the {@code packet} table must contain {@code ≥ 1} row
     * for the submitted job and the {@code job} table must show {@code status = DECOMPOSED}.
     */
    @Test
    void submitJob_nonCacheHit_persistsPacketsAndAdvancesStatusToDecomposed() {
        RawPhaseDef phase = buildSmallPhase(2);
        CanonicalPhaseDef canonical =
                new CanonicalPhaseDef(2, 2, List.of(List.of(0, 1), List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        SubmitJobResponse response = jobService.submitJob(request);

        assertThat(response.jobId()).isNotNull();
        assertThat(response.cacheHit()).isFalse();

        // Verify ≥ 1 UNCLAIMED packet row for this job (production read path via PacketRepository)
        UUID jobId = response.jobId();
        List<PacketRecord> packets = packetRepository.findByJobId(jobId);
        assertThat(packets).isNotEmpty();
        assertThat(packets).allMatch(p -> "UNCLAIMED".equals(p.getStatus()));

        // Rule 2: verify job status is DECOMPOSED via assertj-db Table (independent verifier)
        Table jobTable = assertDb.table("job").build();
        assertThat(jobTable).hasNumberOfRows(1);
        assertThat(jobTable).row(0).value("status").isEqualTo("DECOMPOSED");

        // Rule 2: packet table has ≥ 1 row for the job
        Table packetTable = assertDb.table("packet").build();
        assertThat(packetTable).hasNumberOfRowsGreaterThanOrEqualTo(1);
    }

    /**
     * AC-TEST-CACHE-HIT-NOT-DECOMPOSED: cache-hit path creates no JobRecord and no packets.
     *
     * <p>Uses a JobService wired with a cache service that always returns a hit.
     */
    @Test
    void submitJob_cacheHit_createsNoJobRecordAndNoPackets() {
        CachedResult fakeHit =
                new CachedResult(new byte[32], "default", "{\"bestRank\":0}", Instant.now());

        ResultsCacheService alwaysHitCache = mock(ResultsCacheService.class);
        when(alwaysHitCache.lookup(any(byte[].class), any())).thenReturn(Optional.of(fakeHit));

        AuditService noOpAudit = mock(AuditService.class);
        JobService cacheHitJobService =
                new DefaultJobService(
                        jobRepository,
                        noOpAudit,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        alwaysHitCache,
                        packetDecomposerService,
                        packetRepository);

        RawPhaseDef phase = buildSmallPhase(1);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        SubmitJobResponse response = cacheHitJobService.submitJob(request);

        assertThat(response.cacheHit()).isTrue();

        // Rule 2: job table must be empty (no JobRecord created)
        Table jobTable = assertDb.table("job").build();
        assertThat(jobTable).hasNumberOfRows(0);

        // Rule 2: packet table must be empty
        Table packetTable = assertDb.table("packet").build();
        assertThat(packetTable).hasNumberOfRows(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RawPhaseDef buildSmallPhase(int rowCount) {
        List<RawRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(new RawRow(List.of(new PositionTuple(i, 0), new PositionTuple(i, 1))));
        }
        return new RawPhaseDef(1, rowCount, rows);
    }
}
