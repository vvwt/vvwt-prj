// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.cache;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity for the {@code cached_result} table.
 *
 * <p>Composite primary key: {@code structural_fingerprint} (VARBINARY(32)) + {@code game_mode}
 * (VARCHAR). Spring Data JDBC does not natively support composite keys via a nested ID class. This
 * entity uses a flat model: {@code @Id} is on {@code byte[] structuralFingerprint}; the entity
 * implements {@link Persistable Persistable&lt;byte[]&gt;} with {@link #isNew()} always returning
 * {@code true}, forcing Spring Data JDBC to always INSERT (never UPDATE). {@code gameMode} is a
 * plain {@code @Column} field. The {@link CachedResultId} composite-key value type is used only at
 * the service layer; see {@link #getCompositeKey()} and {@link #setId(CachedResultId)}.
 *
 * <p>Alternative considered: {@code @Embedded} on a composite key class, or a custom converter pair
 * registered via {@code AbstractJdbcConfiguration}. Both rejected: embedded @Id is not universally
 * supported in Spring Data JDBC 3.x, and the converter approach caused Spring Data JDBC to
 * Java-serialize the Map produced by a WritingConverter into the @Id column. The flat-fields +
 * {@code Persistable} pattern is the well-documented, safe approach.
 *
 * <p>Per DEC-35: entity is a mutable POJO (not a record) at the public surface. Per DEC-9: {@code
 * structuralFingerprint} is a 32-byte SHA-256 digest with no UUIDs.
 *
 * <p>Fields per AC-CACHED-RESULT-ENTITY:
 *
 * <ul>
 *   <li>{@code structuralFingerprint} — PK component 1
 *   <li>{@code gameMode} — PK component 2
 *   <li>{@code resultPayloadJson} — canonical JSON of the cached result payload
 *   <li>{@code cachedAt} — timestamp when first written
 *   <li>{@code firstAcceptedJobId} — traceability FK to the originating job
 * </ul>
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-ENTITY; DEC-9, DEC-35
 */
@Table("cached_result")
public class CachedResultEntity implements Persistable<byte[]> {

    /**
     * PK component 1 — 32-byte SHA-256 structural fingerprint. Also used as the Spring Data JDBC
     * {@code @Id} representative; together with {@code gameMode} forms the composite PK.
     */
    @Id
    @Column("structural_fingerprint")
    private byte[] structuralFingerprint;

    @Column("game_mode")
    private String gameMode;

    @Column("result_payload_json")
    private String resultPayloadJson;

    @Column("cached_at")
    private Instant cachedAt;

    @Column("first_accepted_job_id")
    private UUID firstAcceptedJobId;

    // -------------------------------------------------------------------------
    // Composite key bridge (AC-CACHED-RESULT-ENTITY)
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code @Id} field value used by Spring Data JDBC for INSERT identity.
     *
     * <p>Per {@link Persistable}: this must return the same type as the {@code @Id}-annotated field
     * ({@code byte[] structuralFingerprint}). Spring Data JDBC uses this value in INSERT
     * statements. For composite-key lookups, use {@link #getCompositeKey()} or {@link
     * CachedResultRepository#findByKey}.
     *
     * @return the structural fingerprint bytes; {@code null} if not set
     */
    @Override
    public byte[] getId() {
        return structuralFingerprint;
    }

    /**
     * Returns the composite key as a {@link CachedResultId} value object (service-layer
     * convenience).
     *
     * @return composite key; {@code null} if {@code structuralFingerprint} is null
     */
    public CachedResultId getCompositeKey() {
        if (structuralFingerprint == null) return null;
        return new CachedResultId(structuralFingerprint, gameMode);
    }

    /**
     * Sets the entity's PK fields from a {@link CachedResultId}.
     *
     * @param id composite key; must not be {@code null}
     */
    public void setId(CachedResultId id) {
        Objects.requireNonNull(id, "id");
        this.structuralFingerprint = id.structuralFingerprint();
        this.gameMode = id.gameMode();
    }

    /**
     * Cache entries are write-once; always insert, never update.
     *
     * @return always {@code true}
     */
    @Override
    public boolean isNew() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Getters and setters (Spring Data JDBC convention)
    // -------------------------------------------------------------------------

    public byte[] getStructuralFingerprint() {
        return structuralFingerprint;
    }

    public void setStructuralFingerprint(byte[] structuralFingerprint) {
        this.structuralFingerprint = structuralFingerprint;
    }

    public String getGameMode() {
        return gameMode;
    }

    public void setGameMode(String gameMode) {
        this.gameMode = gameMode;
    }

    public String getResultPayloadJson() {
        return resultPayloadJson;
    }

    public void setResultPayloadJson(String resultPayloadJson) {
        this.resultPayloadJson = resultPayloadJson;
    }

    public Instant getCachedAt() {
        return cachedAt;
    }

    public void setCachedAt(Instant cachedAt) {
        this.cachedAt = cachedAt;
    }

    public UUID getFirstAcceptedJobId() {
        return firstAcceptedJobId;
    }

    public void setFirstAcceptedJobId(UUID firstAcceptedJobId) {
        this.firstAcceptedJobId = firstAcceptedJobId;
    }
}
