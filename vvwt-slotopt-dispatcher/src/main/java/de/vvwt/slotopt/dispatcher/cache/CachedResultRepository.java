// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.cache;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC repository for {@link CachedResultEntity}.
 *
 * <p>Per DEC-35: Spring Data {@code CrudRepository} IS the port when the contract maps to CRUD
 * semantics. This interface is the public-package surface; Spring Data generates the implementation
 * proxy at runtime.
 *
 * <p>Composite PK mapping:
 *
 * <ul>
 *   <li>The {@code @Id} field in {@link CachedResultEntity} is {@code structuralFingerprint}
 *       (byte[]). Spring Data JDBC maps this to the {@code structural_fingerprint} column.
 *   <li>Writes: {@link CachedResultEntity#isNew()} always returns {@code true} → always INSERT.
 *   <li>Lookups by composite key use {@link #findByKey} with an explicit {@code @Query} because
 *       Spring Data JDBC derived-query builder cannot query by {@code byte[]} (multi-valued type).
 * </ul>
 *
 * <p>Story: E37S10; AC-CACHED-RESULT-REPOSITORY; DEC-35
 */
public interface CachedResultRepository extends CrudRepository<CachedResultEntity, byte[]> {

    /**
     * Finds a cached result by its composite key components.
     *
     * <p>Uses an explicit {@code @Query} because Spring Data JDBC derived queries cannot filter by
     * {@code byte[]} properties.
     *
     * @param structuralFingerprint the 32-byte SHA-256 fingerprint
     * @param gameMode the game-mode discriminator
     * @return the cached entity, or empty if no cache entry exists for this key
     */
    @Query(
            "SELECT structural_fingerprint, game_mode, result_payload_json, cached_at,"
                    + " first_accepted_job_id FROM cached_result"
                    + " WHERE structural_fingerprint = :fp AND game_mode = :gm")
    Optional<CachedResultEntity> findByKey(
            @Param("fp") byte[] structuralFingerprint, @Param("gm") String gameMode);
}
