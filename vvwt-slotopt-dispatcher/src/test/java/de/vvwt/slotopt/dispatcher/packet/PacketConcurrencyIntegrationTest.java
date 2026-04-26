package de.vvwt.slotopt.dispatcher.packet;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Concurrency integration test for the atomic packet-claim mechanism.
 *
 * <p>Per AC-PULL-PACKET-SERVICE: "concurrent claim returns at most one packet per concurrent
 * caller". This test spawns N+1 concurrent claim calls for N available packets and verifies no
 * double-claim occurs.
 *
 * <p>Uses {@code @SpringBootTest} with embedded H2 (application context) to exercise the real
 * {@link de.vvwt.slotopt.dispatcher.packet.internal.DefaultPullPacketService} with the actual
 * {@link PacketRepository} Spring Data JDBC implementation.
 *
 * <p>RED-first per DEC-22 / AC-PULL-PACKET-SERVICE concurrency clause (E37S08).
 *
 * <p>Story: E37S08; AC-PULL-PACKET-SERVICE; DEC-22
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:dispatcher-concurrency-it-${random.uuid};"
                    + "DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.flyway.locations="
                    + "classpath:db/migration/identity,"
                    + "classpath:db/migration/audit,"
                    + "classpath:db/migration/job,"
                    + "classpath:db/migration/packet",
            "spring.flyway.enabled=true"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PacketConcurrencyIntegrationTest {

    @Autowired private PullPacketService pullPacketService;

    @Autowired private PacketRepository packetRepository;

    @Autowired private KeyRegistrationRepository keyRegistrationRepository;

    private UUID workerId;

    @BeforeEach
    void setUp() {
        // Register a worker for claim tests
        workerId = UUID.randomUUID();
        KeyRegistration reg = new KeyRegistration();
        reg.setWorkerId(workerId);
        reg.setRole("worker");
        reg.setAlgorithm("Ed25519");
        reg.setPublicKeyBytes(new byte[32]);
        reg.setRegisteredAt(Instant.now());
        keyRegistrationRepository.save(reg);
    }

    /**
     * Inserts N unclaimed packets, spawns N+1 concurrent claimers. Verifies that:
     *
     * <ul>
     *   <li>At most N packets are claimed (no double-claim)
     *   <li>At least 1 claimer gets empty (no unclaimed packets left)
     * </ul>
     */
    @Test
    void concurrentClaim_atMostOnePacketPerCaller() throws Exception {
        int packetCount = 3;
        UUID jobId = UUID.randomUUID();

        // Insert N unclaimed packets
        List<PacketRecord> packets = new ArrayList<>();
        for (int i = 0; i < packetCount; i++) {
            PacketRecord p = new PacketRecord();
            p.setPacketId(UUID.randomUUID());
            p.setJobId(jobId);
            p.setPacketPayloadJson(
                    "{\"rankFrom\":" + (i * 100) + ",\"rankTo\":" + ((i + 1) * 100) + "}");
            p.setStatus("UNCLAIMED");
            packets.add(packetRepository.save(p));
        }

        int threadCount = packetCount + 1;
        CountDownLatch startLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<Optional<PacketRecord>> results = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(
                    executor.submit(
                            () -> {
                                try {
                                    startLatch.await();
                                    Optional<PacketRecord> claimed =
                                            pullPacketService.claim(workerId, Set.of("Ed25519"));
                                    results.add(claimed);
                                } catch (Exception e) {
                                    results.add(Optional.empty());
                                }
                            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        executor.shutdown();

        long claimedCount = results.stream().filter(Optional::isPresent).count();
        long emptyCount = results.stream().filter(r -> !r.isPresent()).count();

        // At most N packets claimed (no double-claim)
        assertThat(claimedCount).isLessThanOrEqualTo(packetCount);
        // At least 1 caller found nothing (N+1 callers for N packets)
        assertThat(emptyCount).isGreaterThanOrEqualTo(1);

        // Verify no two callers received the same packet
        List<UUID> claimedPacketIds =
                results.stream()
                        .filter(Optional::isPresent)
                        .map(r -> r.get().getPacketId())
                        .toList();
        assertThat(claimedPacketIds).doesNotHaveDuplicates();
    }
}
