package de.vvwt.slotopt.dispatcher.packet.internal;

import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.packet.PullPacketService;
import de.vvwt.slotopt.dispatcher.packet.WorkerNotFoundException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link PullPacketService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code packet.internal}. All consumers reference
 * {@link PullPacketService} (the public interface), never this class directly (DEC-36).
 *
 * <p>Named {@code DefaultPullPacketService} per DEC-35 naming canon.
 *
 * <p>Atomic claim behavior per AC-PULL-PACKET-SERVICE:
 *
 * <ol>
 *   <li>Verifies the {@code workerId} is registered via {@link KeyRegistrationRepository}. Unknown
 *       worker → throws {@link WorkerNotFoundException}.
 *   <li>Atomically claims one UNCLAIMED packet using a direct JDBC {@code UPDATE ... WHERE status =
 *       'UNCLAIMED' AND id = (SELECT MIN(id) FROM packet WHERE status = 'UNCLAIMED')} pattern. The
 *       condition {@code status = 'UNCLAIMED'} in the WHERE clause acts as an optimistic guard:
 *       concurrent callers that read the same candidate ID will have their UPDATE affect 0 rows,
 *       and they retry by selecting the next available unclaimed packet. This produces correct
 *       at-most-one behavior under H2 and PostgreSQL.
 *   <li>Claims the packet: sets {@code status = CLAIMED}, {@code claimedByWorkerId}, {@code
 *       claimedAt}, {@code timeoutAt = now + packetTimeout}.
 *   <li>Returns the claimed packet record fetched after the successful UPDATE, or empty if no
 *       unclaimed packets exist.
 * </ol>
 *
 * <p>Per Brief C-17: V1 is algorithm-blind. The {@code capabilities} set is informational and does
 * not affect packet routing.
 *
 * <p>Story: E37S08; AC-PULL-PACKET-SERVICE; DEC-35, DEC-36
 */
@Service
public class DefaultPullPacketService implements PullPacketService {

    /** Default packet timeout: 5 minutes (Delivery-tunable). */
    static final Duration PACKET_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Max retry attempts for the optimistic-claim loop. In practice 1-2 retries suffice for typical
     * contention levels; guard against pathological contention.
     */
    private static final int MAX_CLAIM_RETRIES = 10;

    private final PacketRepository packetRepository;
    private final KeyRegistrationRepository keyRegistrationRepository;
    private final JdbcTemplate jdbcTemplate;

    public DefaultPullPacketService(
            PacketRepository packetRepository,
            KeyRegistrationRepository keyRegistrationRepository,
            JdbcTemplate jdbcTemplate) {
        this.packetRepository = packetRepository;
        this.keyRegistrationRepository = keyRegistrationRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses an optimistic-claim loop with a direct JDBC UPDATE guarded by {@code status =
     * 'UNCLAIMED'} to ensure atomic single-claim semantics without requiring SELECT FOR UPDATE
     * (which is not uniformly supported across H2 and PostgreSQL in the same syntax).
     */
    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Optional<PacketRecord> claim(UUID workerId, Set<String> capabilities) {
        // Step 1: verify workerId is registered
        keyRegistrationRepository
                .findByWorkerId(workerId)
                .orElseThrow(
                        () -> new WorkerNotFoundException("Worker not registered: " + workerId));

        // Step 2: optimistic-claim loop
        Instant now = Instant.now();
        Instant timeoutAt = now.plus(PACKET_TIMEOUT);

        for (int attempt = 0; attempt < MAX_CLAIM_RETRIES; attempt++) {
            // Find the lowest-ID UNCLAIMED packet
            Optional<PacketRecord> candidate =
                    packetRepository.findFirstByStatusOrderById("UNCLAIMED");

            if (candidate.isEmpty()) {
                // No UNCLAIMED packets remain
                return Optional.empty();
            }

            Long candidateId = candidate.get().getId();

            // Attempt to claim it atomically: UPDATE succeeds only if it is still UNCLAIMED
            int updated =
                    jdbcTemplate.update(
                            "UPDATE packet "
                                    + "SET status = 'CLAIMED', "
                                    + "    claimed_by_worker_id = ?, "
                                    + "    claimed_at = ?, "
                                    + "    timeout_at = ? "
                                    + "WHERE id = ? AND status = 'UNCLAIMED'",
                            workerId,
                            Timestamp.from(now),
                            Timestamp.from(timeoutAt),
                            candidateId);

            if (updated == 1) {
                // Claim succeeded — fetch the updated record
                Optional<PacketRecord> claimed = packetRepository.findById(candidateId);
                return claimed;
            }
            // updated == 0: another thread claimed this packet first — retry
        }

        // All retries exhausted — no unclaimed packets available
        return Optional.empty();
    }
}
