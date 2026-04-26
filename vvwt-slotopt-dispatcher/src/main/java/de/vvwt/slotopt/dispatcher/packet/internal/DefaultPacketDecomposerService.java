package de.vvwt.slotopt.dispatcher.packet.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketDecomposerService;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.worker.types.JobDef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link PacketDecomposerService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code packet.internal}. All consumers reference
 * {@link PacketDecomposerService} (the public interface), never this class directly (DEC-36).
 *
 * <p>Named {@code DefaultPacketDecomposerService} per DEC-35 naming canon (no {@code I}-prefix on
 * the interface; {@code Default*} prefix on the implementation).
 *
 * <p>Decomposition algorithm per spec section (b) §Packet Decomposition:
 *
 * <ol>
 *   <li>Parse {@code job.jobDefJson} → {@link JobDef} to extract {@code n}
 *   <li>Compute {@code n!} (total permutations)
 *   <li>If {@code n! < 4 × permsPerPacket}: split into exactly 4 packets
 *   <li>Else: split into {@code ceil(n! / permsPerPacket)} packets
 *   <li>Each packet: new UUID, inherited {@code jobId}, rank interval in payload, status UNCLAIMED
 * </ol>
 *
 * <p>Story: E37S08; AC-PACKET-DECOMPOSER-SERVICE; DEC-35, DEC-36
 */
@Service
public class DefaultPacketDecomposerService implements PacketDecomposerService {

    /** Default target: 100 million permutations per packet (30s × ~3.33M perms/sec). */
    static final long PERMS_PER_PACKET = 100_000_000L;

    /** Minimum packet count for small jobs (ensures ≥ 1 packet parallelism). */
    static final int MIN_PACKET_COUNT = 4;

    private final ObjectMapper objectMapper;

    public DefaultPacketDecomposerService() {
        this.objectMapper = new ObjectMapper();
    }

    public DefaultPacketDecomposerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<PacketRecord> decompose(JobRecord job) {
        if (job == null) {
            throw new IllegalArgumentException("job must not be null");
        }

        // Parse JobDef to get n
        JobDef jobDef;
        try {
            jobDef = objectMapper.readValue(job.getJobDefJson(), JobDef.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to parse jobDefJson for job " + job.getJobId() + ": " + e.getMessage(),
                    e);
        }

        int n = jobDef.n();
        long totalPerms = factorial(n);

        // Determine packet count
        int packetCount;
        if (totalPerms < (long) MIN_PACKET_COUNT * PERMS_PER_PACKET) {
            packetCount = MIN_PACKET_COUNT;
        } else {
            packetCount = (int) Math.ceil((double) totalPerms / PERMS_PER_PACKET);
        }

        // Partition [0, n!) into packetCount contiguous intervals
        List<PacketRecord> packets = new ArrayList<>(packetCount);
        long rankFrom = 0L;
        for (int i = 0; i < packetCount; i++) {
            long rankTo;
            if (i == packetCount - 1) {
                // Last packet: takes all remaining perms to avoid rounding gaps
                rankTo = totalPerms;
            } else {
                rankTo = rankFrom + (totalPerms / packetCount);
                // Distribute remainder: first (totalPerms % packetCount) packets get one extra
                if (i < (int) (totalPerms % packetCount)) {
                    rankTo++;
                }
            }

            PacketRecord packet = buildPacket(job.getJobId(), n, jobDef, rankFrom, rankTo);
            packets.add(packet);
            rankFrom = rankTo;
        }

        return packets;
    }

    private PacketRecord buildPacket(UUID jobId, int n, JobDef jobDef, long rankFrom, long rankTo) {
        PacketRecord packet = new PacketRecord();
        packet.setPacketId(UUID.randomUUID());
        packet.setJobId(jobId);

        // Payload: JSON with rankFrom, rankTo, n, and canonicalPhaseDef for the worker
        String payload;
        try {
            payload =
                    objectMapper.writeValueAsString(
                            new PacketPayload(jobId, n, jobDef, rankFrom, rankTo));
        } catch (JsonProcessingException e) {
            // Fallback to minimal JSON if serialization fails (should not happen in production)
            payload =
                    "{\"jobId\":\""
                            + jobId
                            + "\",\"rankFrom\":"
                            + rankFrom
                            + ",\"rankTo\":"
                            + rankTo
                            + "}";
        }

        packet.setPacketPayloadJson(payload);
        packet.setStatus("UNCLAIMED");
        return packet;
    }

    /**
     * Computes {@code n!} for {@code n} in [0, 20]. Returns {@link Long#MAX_VALUE} for n > 20
     * (overflow guard — N-cap of 15 means max is 15! = 1.3e12, well within long range).
     */
    static long factorial(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be non-negative");
        if (n > 20) return Long.MAX_VALUE; // overflow guard
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal payload record for JSON serialization
    // -------------------------------------------------------------------------

    record PacketPayload(UUID jobId, int n, JobDef jobDef, long rankFrom, long rankTo) {}
}
