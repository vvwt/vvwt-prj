package de.vvwt.dispatcher.cache;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code cached_results} table (AC6 of E01S09).
 *
 * <p>Schema (Postgres preferred, H2 acceptable for embedded per DEC-3):
 *
 * <pre>
 *   cached_results(
 *     fingerprint              BYTEA         NOT NULL,
 *     score_fn_version         INT           NOT NULL,
 *     canonicalization_version INT           NOT NULL,
 *     best_rank                BIGINT        NOT NULL,
 *     best_score               DOUBLE PRECISION NOT NULL,
 *     n                        INT           NOT NULL,
 *     computed_at              TIMESTAMPTZ   NOT NULL,
 *     source_job_id            UUID          NOT NULL,
 *     PRIMARY KEY (fingerprint, score_fn_version, canonicalization_version)
 *   )
 * </pre>
 */
@Entity
@Table(name = "cached_results")
public class CachedResultEntity {

    @EmbeddedId private CachedResultId id;

    @Column(name = "best_rank", nullable = false)
    private long bestRank;

    @Column(name = "best_score", nullable = false)
    private double bestScore;

    @Column(name = "n", nullable = false)
    private int n;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "source_job_id", nullable = false)
    private UUID sourceJobId;

    /** JPA no-arg constructor. */
    protected CachedResultEntity() {}

    /**
     * Creates a fully-populated entity.
     *
     * @param id composite primary key
     * @param bestRank best permutation rank found by workers
     * @param bestScore variety score for {@code bestRank}
     * @param n number of avatars in the phase
     * @param computedAt timestamp when the result was finalized
     * @param sourceJobId the job that produced this result first
     */
    public CachedResultEntity(
            CachedResultId id,
            long bestRank,
            double bestScore,
            int n,
            Instant computedAt,
            UUID sourceJobId) {
        this.id = id;
        this.bestRank = bestRank;
        this.bestScore = bestScore;
        this.n = n;
        this.computedAt = computedAt;
        this.sourceJobId = sourceJobId;
    }

    public CachedResultId getId() {
        return id;
    }

    public long getBestRank() {
        return bestRank;
    }

    public double getBestScore() {
        return bestScore;
    }

    public int getN() {
        return n;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public UUID getSourceJobId() {
        return sourceJobId;
    }
}
