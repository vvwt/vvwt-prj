package de.vvwt.dispatcher.packet;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.dispatcher.job.JobRecord;
import de.vvwt.dispatcher.job.JobRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for {@link PacketRepository} concurrency semantics (AC7).
 *
 * <p>Tests the {@code SELECT FOR UPDATE SKIP LOCKED} query using H2 in PostgreSQL mode.
 *
 * <h2>Concurrency test design note</h2>
 *
 * H2 in-memory with {@code @DataJpaTest} uses a single shared connection. True concurrent {@code
 * SKIP LOCKED} requires multiple independent connections. The sequential correctness test verifies
 * the JPQL query logic; the actual SKIP LOCKED guarantees are validated by the PostgreSQL
 * integration test suite (run manually against a live DB).
 *
 * <p>This test verifies: no packet is assigned twice when sequential claims are made, and each
 * claim returns a distinct packet (the core safety invariant).
 *
 * <p>See Story E01S07 AC7.
 */
@DataJpaTest
class PacketConcurrencyIntegrationTest {

    @Autowired private PacketRepository packetRepository;

    @Autowired private JobRepository jobRepository;

    @Autowired private TransactionTemplate transactionTemplate;

    private UUID jobId;

    @BeforeEach
    @Transactional
    void setup() {
        jobId = UUID.randomUUID();
        // Create a 'ready' job
        JobRecord job =
                new JobRecord(
                        jobId,
                        UUID.randomUUID(),
                        1,
                        "{}",
                        "{\"rowCount\":3,\"avatarCount\":6,\"rows\":[[0,1],[2,3],[4,5]]}",
                        new byte[32],
                        "ready",
                        Instant.now());
        job.setPacketCount(100);
        jobRepository.save(job);

        // Insert 100 pending packets
        List<PacketRecord> packets = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            packets.add(new PacketRecord(UUID.randomUUID(), jobId, i * 1000L, (i + 1) * 1000L));
        }
        packetRepository.saveAll(packets);
    }

    /**
     * AC7 sequential correctness: 50 sequential claims return 50 distinct packets.
     *
     * <p>Each claim is in its own transaction to simulate independent worker requests. Verifies the
     * query logic and lock-then-assign pattern produces no duplicates.
     */
    @Test
    void sequentialClaims_returnDistinctPackets() {
        UUID workerKeyId = UUID.randomUUID();
        int claimCount = 50;
        List<UUID> assignedIds = new ArrayList<>();

        for (int i = 0; i < claimCount; i++) {
            UUID assignedId =
                    transactionTemplate.execute(
                            status -> {
                                Optional<PacketRecord> opt =
                                        packetRepository.findAndLockNextPendingPacket();
                                if (opt.isEmpty()) {
                                    return null;
                                }
                                PacketRecord packet = opt.get();
                                packet.assign(workerKeyId, Instant.now());
                                packetRepository.save(packet);
                                return packet.getPacketId();
                            });
            if (assignedId != null) {
                assignedIds.add(assignedId);
            }
        }

        // All 50 claims should succeed
        assertThat(assignedIds).hasSize(claimCount);

        // All assigned IDs must be distinct (no duplicate assignments)
        long distinctCount = assignedIds.stream().distinct().count();
        assertThat(distinctCount).isEqualTo(claimCount);
    }

    /** AC7 invariant: after 100 sequential claims, no pending packets remain for the job. */
    @Test
    void allPacketsClaimed_noPendingRemain() {
        UUID workerKeyId = UUID.randomUUID();

        // Claim all 100 packets
        for (int i = 0; i < 100; i++) {
            transactionTemplate.execute(
                    status -> {
                        return packetRepository
                                .findAndLockNextPendingPacket()
                                .map(
                                        packet -> {
                                            packet.assign(workerKeyId, Instant.now());
                                            packetRepository.save(packet);
                                            return packet.getPacketId();
                                        })
                                .orElse(null);
                    });
        }

        // The 101st claim should return empty (no more pending packets)
        Optional<PacketRecord> result =
                transactionTemplate.execute(
                        status -> packetRepository.findAndLockNextPendingPacket());

        assertThat(result).isEmpty();
    }

    /** AC7 status invariant: claimed packets are in 'assigned' state, not 'pending'. */
    @Test
    void claimedPacket_hasAssignedStatus() {
        UUID workerKeyId = UUID.randomUUID();

        UUID packetId =
                transactionTemplate.execute(
                        status -> {
                            PacketRecord packet =
                                    packetRepository
                                            .findAndLockNextPendingPacket()
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "No pending packets"));
                            packet.assign(workerKeyId, Instant.now());
                            packetRepository.save(packet);
                            return packet.getPacketId();
                        });

        // Verify in a separate transaction
        PacketRecord saved =
                transactionTemplate.execute(
                        status -> packetRepository.findById(packetId).orElseThrow());

        assertThat(saved.getStatus()).isEqualTo("assigned");
        assertThat(saved.getAssignedTo()).isEqualTo(workerKeyId);
        assertThat(saved.getAttempts()).isEqualTo(1);
    }
}
