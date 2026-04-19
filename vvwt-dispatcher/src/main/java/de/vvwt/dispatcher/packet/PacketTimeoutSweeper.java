package de.vvwt.dispatcher.packet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background sweeper that reissues timed-out packets and permanently fails packets that have
 * exceeded the maximum reissue count (E01S07 AC8).
 *
 * <h2>Sweep logic</h2>
 *
 * <ol>
 *   <li>Compute {@code cutoff = now() - packetTimeoutMinutes}.
 *   <li>Load all {@code "assigned"} packets where {@code assigned_at < cutoff}.
 *   <li>For each:
 *       <ul>
 *         <li>Append a reissue entry to {@code reissue_history} recording {@code (timestamp,
 *             workerKeyId, attemptNumber)} (AC8 audit trail).
 *         <li>If {@code attempts >= maxReissueCount}: mark {@code status='failed'}; log at ERROR
 *             (AC8).
 *         <li>Else: mark {@code status='pending'}, clear {@code assignedTo}/{@code assignedAt}; log
 *             at INFO (AC8).
 *       </ul>
 * </ol>
 *
 * <p>The sweeper does NOT immediately fail the owning job when a packet is failed — that
 * cross-cutting job-level failure state is deferred to E01S08 (result intake & finalization). In
 * Phase 1, the failed packet is logged at ERROR and remains visible in the {@code packets} table
 * with {@code status='failed'} so operators can observe it.
 *
 * <p>Requires {@code @EnableScheduling} on {@link de.vvwt.dispatcher.DispatcherApplication}.
 *
 * <p>See Story E01S07 AC8.
 */
@Component
public class PacketTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(PacketTimeoutSweeper.class);

    private final PacketRepository packetRepository;

    @Value("${dispatcher.packet.timeout:5}")
    private long packetTimeoutMinutes;

    @Value("${dispatcher.sweeper.max-reissue-count:5}")
    private int maxReissueCount;

    public PacketTimeoutSweeper(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
    }

    /**
     * Runs at the configured interval (default 30 s).
     *
     * <p>Uses {@code fixedDelayString} so the next sweep starts only after the current one
     * completes — no concurrent sweep runs.
     */
    @Scheduled(fixedDelayString = "${dispatcher.sweeper.interval-ms:30000}")
    @Transactional
    public void sweepTimedOutPackets() {
        Instant cutoff = Instant.now().minusSeconds(packetTimeoutMinutes * 60L);
        List<PacketRecord> timedOut = packetRepository.findTimedOutPackets(cutoff);

        if (timedOut.isEmpty()) {
            return; // nothing to sweep
        }

        log.debug("Sweeper found {} timed-out packet(s) (cutoff={})", timedOut.size(), cutoff);

        for (PacketRecord packet : timedOut) {
            String reissueEntry = buildReissueHistoryEntry(packet);
            packet.appendReissueHistory(reissueEntry);

            if (packet.getAttempts() >= maxReissueCount) {
                // Max reissue threshold reached — permanently fail (AC8)
                packet.markFailed();
                log.error(
                        "Packet FAILED after max reissues: packetId={} jobId={} attempts={}"
                                + " maxReissueCount={}",
                        packet.getPacketId(),
                        packet.getJobId(),
                        packet.getAttempts(),
                        maxReissueCount);
            } else {
                // Reissue: return to pending pool (AC8)
                UUID previousWorker = packet.getAssignedTo();
                packet.reissueToPending();
                log.info(
                        "Packet reissued to pending: packetId={} jobId={} previousWorkerKeyId={}"
                                + " attemptNumber={}",
                        packet.getPacketId(),
                        packet.getJobId(),
                        previousWorker,
                        packet.getAttempts());
            }

            packetRepository.save(packet);
        }
    }

    /**
     * Builds a single JSON entry for the {@code reissue_history} column.
     *
     * <p>Format (no enclosing array brackets — {@link PacketRecord#appendReissueHistory} wraps):
     * {@code {"timestamp":"<ISO-8601>","workerKeyId":"<UUID>","attemptNumber":<N>}}
     */
    private static String buildReissueHistoryEntry(PacketRecord packet) {
        return String.format(
                "{\"timestamp\":\"%s\",\"workerKeyId\":\"%s\",\"attemptNumber\":%d}",
                Instant.now(), packet.getAssignedTo(), packet.getAttempts());
    }

    // -------------------------------------------------------------------------
    // Package-private setters for test injection (avoid @SpringBootTest overhead)
    // -------------------------------------------------------------------------

    void setPacketTimeoutMinutes(long packetTimeoutMinutes) {
        this.packetTimeoutMinutes = packetTimeoutMinutes;
    }

    void setMaxReissueCount(int maxReissueCount) {
        this.maxReissueCount = maxReissueCount;
    }
}
