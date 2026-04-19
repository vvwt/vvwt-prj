package de.vvwt.dispatcher.packet;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.dispatcher.job.JobRecord;
import de.vvwt.dispatcher.job.JobRepository;
import de.vvwt.worker.types.CanonicalPhaseDef;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decomposes a queued job into rank-interval packets and transitions the job to {@code "ready"}.
 *
 * <h2>Decomposition algorithm (AC1, AC2)</h2>
 *
 * <ol>
 *   <li>Claim the job atomically: {@code UPDATE jobs SET status='decomposing' WHERE
 *       status='queued'}. If another process already claimed it, abort.
 *   <li>Parse {@code canonicalPhaseDefJson} → {@link CanonicalPhaseDef}; read {@code rowCount} = N.
 *   <li>Compute {@code permsPerPacket = targetWallClockSec × refRateHz}.
 *   <li>Compute {@code n!} using BigInteger (handles N=15 with 1.3 trillion perms).
 *   <li>If {@code n! < 4 × permsPerPacket}: use exactly 4 packets (guarantees parallelism on small
 *       N).
 *   <li>Else: {@code packetCount = ceil(n! / permsPerPacket)}.
 *   <li>Divide {@code [0, n!)} into {@code packetCount} even rank intervals (last packet absorbs
 *       the remainder if any).
 *   <li>Insert all {@link PacketRecord} rows with {@code status='pending'}, {@code attempts=0}.
 *   <li>Update job: {@code status='ready'}, {@code packetCount=packetCount}.
 * </ol>
 *
 * <p>See Story E01S07 AC1, AC2 and DEC-11.
 */
@Service
public class PacketDecomposerService {

    private static final Logger log = LoggerFactory.getLogger(PacketDecomposerService.class);

    /** Minimum packet count for small-N jobs (AC2: guaranteed parallelism). */
    static final int MIN_PACKET_COUNT = 4;

    private final JobRepository jobRepository;
    private final PacketRepository packetRepository;
    private final ObjectMapper objectMapper;

    @Value("${dispatcher.packet.target-wall-clock-sec:30}")
    private long targetWallClockSec;

    @Value("${dispatcher.packet.ref-rate-hz:3333333}")
    private long refRateHz;

    public PacketDecomposerService(
            JobRepository jobRepository,
            PacketRepository packetRepository,
            ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.packetRepository = packetRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Decomposes a single queued job into packets.
     *
     * @param jobId the job to decompose
     * @return the number of packets created
     * @throws IllegalStateException if the job does not exist or has already been claimed
     */
    @Transactional
    public int decompose(UUID jobId) {
        // Load the job to get its canonical phase def
        JobRecord job =
                jobRepository
                        .findById(jobId)
                        .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

        // Atomically claim: only succeeds if status is still 'queued'
        int claimed = jobRepository.claimForDecomposition(jobId);
        if (claimed == 0) {
            throw new IllegalStateException(
                    "Job "
                            + jobId
                            + " has already been claimed for decomposition (status was not"
                            + " 'queued')");
        }

        // Parse the canonical phase definition to get N
        CanonicalPhaseDef phaseDef = parseCanonicalPhaseDef(job.getCanonicalPhaseDefJson());
        int n = phaseDef.rowCount();

        // Compute packet boundaries
        BigInteger nFactorial = factorial(n);
        BigInteger permsPerPacket =
                BigInteger.valueOf(targetWallClockSec).multiply(BigInteger.valueOf(refRateHz));

        int packetCount = computePacketCount(nFactorial, permsPerPacket);

        // Create packets: divide [0, n!) into packetCount intervals
        List<PacketRecord> packets = buildPackets(jobId, nFactorial, packetCount);
        packetRepository.saveAll(packets);

        // Transition job to ready
        job.setStatus("ready");
        job.setPacketCount(packetCount);
        jobRepository.save(job);

        log.info(
                "Decomposed job {} (N={}) into {} packets (permsPerPacket={})",
                jobId,
                n,
                packetCount,
                permsPerPacket);
        return packetCount;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Computes the number of packets for a job.
     *
     * <p>AC2 rule: if {@code n! < 4 × permsPerPacket} → use exactly 4 packets. Otherwise: {@code
     * packetCount = ceil(n! / permsPerPacket)}.
     */
    int computePacketCount(BigInteger nFactorial, BigInteger permsPerPacket) {
        BigInteger minThreshold = permsPerPacket.multiply(BigInteger.valueOf(MIN_PACKET_COUNT));
        if (nFactorial.compareTo(minThreshold) < 0) {
            return MIN_PACKET_COUNT;
        }
        // ceil(nFactorial / permsPerPacket) = (nFactorial + permsPerPacket - 1) / permsPerPacket
        BigInteger[] divRem = nFactorial.divideAndRemainder(permsPerPacket);
        BigInteger count =
                divRem[1].equals(BigInteger.ZERO) ? divRem[0] : divRem[0].add(BigInteger.ONE);
        // packetCount fits in an int for all practical N (N=15 yields ~13000 packets)
        return count.intValueExact();
    }

    /**
     * Divides the rank interval {@code [0, nFactorial)} into {@code packetCount} even intervals.
     *
     * <p>The last packet absorbs any remainder from integer division.
     */
    private List<PacketRecord> buildPackets(UUID jobId, BigInteger nFactorial, int packetCount) {
        BigInteger baseSize = nFactorial.divide(BigInteger.valueOf(packetCount));

        List<PacketRecord> packets = new ArrayList<>(packetCount);
        BigInteger cursor = BigInteger.ZERO;

        for (int i = 0; i < packetCount; i++) {
            BigInteger size;
            if (i == packetCount - 1) {
                // Last packet absorbs everything remaining (handles remainder)
                size = nFactorial.subtract(cursor);
            } else {
                size = baseSize;
            }
            long rankFrom = cursor.longValueExact();
            long rankTo = cursor.add(size).longValueExact();
            packets.add(new PacketRecord(UUID.randomUUID(), jobId, rankFrom, rankTo));
            cursor = cursor.add(size);
        }
        return packets;
    }

    /**
     * Computes N! using BigInteger to handle large N without overflow.
     *
     * @param n non-negative integer (rowCount from CanonicalPhaseDef)
     * @return N! as a BigInteger
     */
    static BigInteger factorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be non-negative, got: " + n);
        }
        BigInteger result = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            result = result.multiply(BigInteger.valueOf(i));
        }
        return result;
    }

    private CanonicalPhaseDef parseCanonicalPhaseDef(String json) {
        try {
            return objectMapper.readValue(json, CanonicalPhaseDef.class);
        } catch (Exception parseError) {
            throw new IllegalStateException(
                    "Failed to parse canonicalPhaseDefJson: " + parseError.getMessage(),
                    parseError);
        }
    }
}
