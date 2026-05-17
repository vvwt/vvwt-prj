package de.vvwt.slotopt.dispatcher.result;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code packet_result} table.
 *
 * <p>Mutable POJO (NOT a record) per DEC-35: entities remain Spring Data JDBC POJOs at the public
 * surface.
 *
 * <p>DEC-9: carries only structural optimization data — {@code bestRank} / {@code bestScore} and
 * packet/job association keys. No team UUIDs, names, or identity-bearing attributes cross this
 * boundary (AC-GOV-DEC9-STRUCTURAL-ONLY).
 *
 * <p>DEC-6 first-valid-wins: the UNIQUE constraint on {@code packet_id} enforces that only the
 * first accepted result is retained; duplicate inserts are rejected at the DB level
 * (AC-ERR-DUPLICATE-RESULT-NO-CORRUPTION).
 *
 * <p>Schema: {@code db/migration/result/V8__create_packet_result_table.sql} (DEC-26/DEC-46 Rule 1
 * analogous).
 *
 * <p>Story: E60S02; AC-GOV-QUERYABLE-SUBSTRATE; DEC-9, DEC-35
 */
@Table("packet_result")
public class PacketResult {

    @Id private Long id;

    /**
     * UUID of the packet whose first valid result is retained. UNIQUE per DB constraint. NOT NULL.
     */
    private UUID packetId;

    /** UUID of the owning job. NOT NULL. Used for bulk-by-job queries (E60S03 substrate). */
    private UUID jobId;

    /**
     * Best rank from the worker's result payload (the lower the better — smallest rank is the
     * global optimum candidate). NOT NULL.
     *
     * <p>DEC-9: structural only — no team identity attached.
     */
    private int bestRank;

    /**
     * Best score from the worker's result payload. NOT NULL.
     *
     * <p>DEC-9: structural only — no team identity attached.
     */
    private double bestScore;

    // -------------------------------------------------------------------------
    // Getters and setters (Spring Data JDBC convention)
    // -------------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getPacketId() {
        return packetId;
    }

    public void setPacketId(UUID packetId) {
        this.packetId = packetId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public int getBestRank() {
        return bestRank;
    }

    public void setBestRank(int bestRank) {
        this.bestRank = bestRank;
    }

    public double getBestScore() {
        return bestScore;
    }

    public void setBestScore(double bestScore) {
        this.bestScore = bestScore;
    }
}
