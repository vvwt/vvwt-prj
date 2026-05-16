// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.audit.AuditService;
import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.job.JobService;
import de.vvwt.slotopt.dispatcher.job.SubmitJobRequest;
import de.vvwt.slotopt.dispatcher.job.SubmitJobResponse;
import de.vvwt.slotopt.dispatcher.job.internal.DefaultJobService;
import de.vvwt.slotopt.dispatcher.packet.internal.DefaultPacketDecomposerService;
import de.vvwt.slotopt.dispatcher.packet.internal.DefaultPullPacketService;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test: after submit-job, a registered worker can claim one of that job's packets via
 * {@link PullPacketService#claim}. Verifies the decomposition→pull seam end-to-end at the service
 * layer.
 *
 * <p>Applies DEC-26 / DEC-46 three-rule DAO governance:
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> Flyway loads identity ({@code V1}), job
 *       ({@code V3}), and packet ({@code V4}) migrations.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> assertions target the {@link
 *       PacketRecord} returned by {@code PullPacketService.claim()}, not by the repository's own
 *       query methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> worker registration is inserted directly via {@link
 *       KeyRegistrationRepository#save} (not a fixture helper); the test exercises the write path
 *       of registration and the read path of claim.
 * </ol>
 *
 * <p>Story: E60S01; AC-TEST-PULL-PACKET-SERVES-DECOMPOSED-JOB; AC-GOV-DAO-IT-THREE-RULE; DEC-26,
 * DEC-46
 */
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({DefaultPacketDecomposerService.class, DefaultPullPacketService.class})
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-pull-after-submit-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations="
                    + "classpath:db/migration/identity,"
                    + "classpath:db/migration/job,"
                    + "classpath:db/migration/packet",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PullPacketAfterSubmitIT {

    @Autowired private JobRepository jobRepository;

    @Autowired private PacketRepository packetRepository;

    @Autowired private PacketDecomposerService packetDecomposerService;

    @Autowired private PullPacketService pullPacketService;

    @Autowired private KeyRegistrationRepository keyRegistrationRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private JobService jobService;

    private UUID registeredWorkerId;

    @BeforeEach
    void setUp() {
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

        // Register a worker so PullPacketService.claim() succeeds
        registeredWorkerId = UUID.randomUUID();
        KeyRegistration reg = new KeyRegistration();
        reg.setWorkerId(registeredWorkerId);
        reg.setAlgorithm("Ed25519");
        reg.setPublicKeyBytes(new byte[32]);
        reg.setRole("WORKER");
        reg.setRegisteredAt(Instant.now());
        keyRegistrationRepository.save(reg);
    }

    /**
     * AC-TEST-PULL-PACKET-SERVES-DECOMPOSED-JOB (E60S01):
     *
     * <p>After a job is submitted and decomposed, a registered worker calling {@code
     * PullPacketService.claim()} receives one of that job's packets.
     */
    @Test
    void submitAndDecompose_thenClaimByWorker_returnspacket() {
        // Submit a non-cache-hit job → should decompose into packets
        RawPhaseDef phase = buildSmallPhase(2);
        CanonicalPhaseDef canonical =
                new CanonicalPhaseDef(2, 2, List.of(List.of(0, 1), List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        SubmitJobResponse response = jobService.submitJob(request);
        assertThat(response.cacheHit()).isFalse();

        // Registered worker claims a packet
        Optional<PacketRecord> claimed = pullPacketService.claim(registeredWorkerId, Set.of());

        assertThat(claimed).isPresent();
        assertThat(claimed.get().getJobId()).isEqualTo(response.jobId());
        assertThat(claimed.get().getStatus()).isEqualTo("CLAIMED");
    }

    /**
     * AC-ERR-PULL-EMPTY-WHEN-NO-PACKETS (E60S01):
     *
     * <p>When no packets exist (no jobs submitted), {@code pull-packet} returns empty — not an
     * error.
     */
    @Test
    void noPackets_claimReturnsEmpty() {
        Optional<PacketRecord> result = pullPacketService.claim(registeredWorkerId, Set.of());
        assertThat(result).isEmpty();
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
