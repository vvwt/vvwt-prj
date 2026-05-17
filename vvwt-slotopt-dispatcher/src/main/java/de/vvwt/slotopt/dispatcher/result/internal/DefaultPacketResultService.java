package de.vvwt.slotopt.dispatcher.result.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.dispatcher.result.PacketResult;
import de.vvwt.slotopt.dispatcher.result.PacketResultRepository;
import de.vvwt.slotopt.dispatcher.result.PacketResultService;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link PacketResultService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code result.internal}. All consumers reference
 * {@link PacketResultService} (the public interface) exclusively (DEC-36).
 *
 * <p>Retains the first-valid per-packet result in the {@code packet_result} table. The retention is
 * atomic with the {@code RESULT_RECEIVED} status transition on the owning {@code PacketRecord}
 * (AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT) because both writes share the caller's
 * {@code @Transactional} boundary.
 *
 * <p>DEC-9: only structural data ({@code bestRank} / {@code bestScore} + association keys) is
 * retained. No team identity crosses the optimizer service boundary.
 *
 * <p>Story: E60S02; AC-GOV-QUERYABLE-SUBSTRATE; AC-TEST-RESULT-RETAINED-ON-ACCEPT;
 * AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT; AC-ERR-DUPLICATE-RESULT-NO-CORRUPTION;
 * AC-ERR-RETENTION-REJECTS-MALFORMED-RESULT; DEC-6, DEC-9, DEC-35, DEC-36, DEC-58, DEC-72
 */
@Service
class DefaultPacketResultService implements PacketResultService {

    private static final Logger LOG = Logger.getLogger(DefaultPacketResultService.class.getName());

    private final PacketResultRepository repository;
    private final ObjectMapper objectMapper;

    DefaultPacketResultService(PacketResultRepository repository) {
        this.repository = repository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs within the caller's transaction (MANDATORY propagation not enforced here — the
     * {@code @Transactional} on the caller, {@link
     * de.vvwt.slotopt.dispatcher.result.internal.DefaultSubmitResultService#submit}, covers both
     * the status update and this retention write). If this method throws, the caller's transaction
     * rolls back, leaving the packet status at {@code CLAIMED} and no retention row created
     * (AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT).
     *
     * <p>Idempotent for exact duplicates: if a result for {@code packetId} already exists (UNIQUE
     * constraint violation), the {@link DataIntegrityViolationException} is caught and the existing
     * result is left unchanged. This implements AC-ERR-DUPLICATE-RESULT-NO-CORRUPTION without
     * requiring a pre-check read (which would be racey).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void retainResult(UUID packetId, UUID jobId, String resultPayloadJson) {
        int bestRank = extractBestRank(resultPayloadJson);
        double bestScore = extractBestScore(resultPayloadJson);

        PacketResult pr = new PacketResult();
        pr.setPacketId(packetId);
        pr.setJobId(jobId);
        pr.setBestRank(bestRank);
        pr.setBestScore(bestScore);

        try {
            repository.save(pr);
        } catch (DataIntegrityViolationException e) {
            // Idempotent duplicate: the packet already has a retained result.
            // First-valid-wins (DEC-6) means the existing row is authoritative — ignore.
            LOG.log(
                    Level.WARNING,
                    "Duplicate retention attempt for packet {0} — first-valid result preserved"
                            + " (DEC-6 first-valid-wins, AC-ERR-DUPLICATE-RESULT-NO-CORRUPTION)",
                    packetId);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<PacketResult> findResultsByJobId(UUID jobId) {
        return repository.findAllByJobId(jobId);
    }

    // -------------------------------------------------------------------------
    // Private helpers — payload extraction (AC-ERR-RETENTION-REJECTS-MALFORMED-RESULT)
    // -------------------------------------------------------------------------

    /**
     * Extracts {@code bestRank} (integer) from the result payload JSON.
     *
     * @throws IllegalArgumentException if the field is absent or non-numeric
     */
    private int extractBestRank(String resultPayloadJson) {
        JsonNode node = parsePayload(resultPayloadJson);
        JsonNode rankNode = node.get("bestRank");
        if (rankNode == null || rankNode.isNull() || !rankNode.isNumber()) {
            throw new IllegalArgumentException(
                    "bestRank missing or non-numeric in result payload — result rejected"
                            + " (AC-ERR-RETENTION-REJECTS-MALFORMED-RESULT)");
        }
        return rankNode.intValue();
    }

    /**
     * Extracts {@code bestScore} (double) from the result payload JSON.
     *
     * @throws IllegalArgumentException if the field is absent or non-numeric
     */
    private double extractBestScore(String resultPayloadJson) {
        JsonNode node = parsePayload(resultPayloadJson);
        JsonNode scoreNode = node.get("bestScore");
        if (scoreNode == null || scoreNode.isNull() || !scoreNode.isNumber()) {
            throw new IllegalArgumentException(
                    "bestScore missing or non-numeric in result payload — result rejected"
                            + " (AC-ERR-RETENTION-REJECTS-MALFORMED-RESULT)");
        }
        return scoreNode.doubleValue();
    }

    private JsonNode parsePayload(String resultPayloadJson) {
        try {
            return objectMapper.readTree(resultPayloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse resultPayloadJson: " + e.getMessage(), e);
        }
    }
}
