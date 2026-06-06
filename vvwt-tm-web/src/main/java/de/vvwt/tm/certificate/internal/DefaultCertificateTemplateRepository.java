// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.certificate.internal;

import de.vvwt.tm.certificate.CertificateTemplateMetadata;
import de.vvwt.tm.certificate.CertificateTemplateRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Default implementation of {@link CertificateTemplateRepository} — JdbcTemplate-based persistence
 * for certificate template metadata (E12S04, E23S06, E23S07).
 *
 * <p>Renamed from {@code CertificateTemplateRepository} to {@code
 * DefaultCertificateTemplateRepository} as part of E23S07 (Q-1a TDD-extract per DEC-35 naming
 * canon). The public interface {@link CertificateTemplateRepository} is the port; this class is the
 * adapter in {@code certificate.internal.*}.
 *
 * <p>Per DEC-35 Item 2: this is a hand-authored interface (port over adapter) because the
 * certificate_template table uses {@link JdbcTemplate} directly — not Spring Data CRUD semantics.
 * The interface is hand-authored per DEC-35 Item 2; this impl lives in {@code .internal.*} per
 * DEC-35 naming canon.
 *
 * <p>Uses {@link JdbcTemplate} directly because the certificate_template table has {@code
 * tournament_id} as its sole primary key — one template per tournament, so there is no entity graph
 * to map and no Spring Data JDBC aggregate to manage. A simple upsert (MERGE statement) is more
 * natural here than the INSERT/UPDATE decision in {@code TenantScopedRepository}.
 *
 * <h2>Tenant scoping (AC8, DEC-5, DEC-17)</h2>
 *
 * <p>Tenant isolation is enforced at the service layer via {@link
 * de.vvwt.tm.tournament.TournamentRepository#findById(Object)}, which returns empty if the
 * tournament does not exist or belongs to a different tenant. This repository itself only operates
 * on the {@code tournament_id} FK — it trusts that the caller has validated tournament ownership.
 *
 * <h2>Flyway schema (DEC-14, DEC-25, E23S06)</h2>
 *
 * <p>Schema is managed by Flyway migration {@code db/migration/certificate/V1__initial_schema.sql}
 * (per-module, E23S06, DEC-25). The root {@code V15__e12s04_certificate_template.sql} remains on
 * disk during the parallel phase; it is deleted at E23S10 Cutover-2.
 *
 * <p>E71S02: V2 migration adds two nullable columns {@code photo_aspect_ratio_width} and {@code
 * photo_aspect_ratio_height} for the per-template crop ratio override (AC1).
 *
 * @see CertificateTemplateRepository
 * @see de.vvwt.tm.certificate.internal.DefaultCertificateTemplateService
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-35.md">DEC-35
 *     Item 2</a>
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-26.md">DEC-26</a>
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E23S07.story.md">Story
 *     E23S07 — D-16</a>
 */
@Repository("defaultCertificateTemplateRepository")
public class DefaultCertificateTemplateRepository implements CertificateTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    public DefaultCertificateTemplateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Returns the certificate template metadata for the given tournament, if one exists.
     *
     * @param tournamentId the tournament UUID
     * @return the metadata, or {@link Optional#empty()} if no template is stored
     */
    @Override
    public Optional<CertificateTemplateMetadata> findByTournamentId(UUID tournamentId) {
        String sql =
                """
                SELECT tournament_id, filename, format, upload_timestamp, file_size_bytes,
                       photo_aspect_ratio_width, photo_aspect_ratio_height
                FROM certificate_template
                WHERE tournament_id = ?
                """;
        List<CertificateTemplateMetadata> results =
                jdbcTemplate.query(sql, this::mapRow, tournamentId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // -------------------------------------------------------------------------
    // Write (upsert — AC1, AC4)
    // -------------------------------------------------------------------------

    /**
     * Inserts or replaces the certificate template metadata for the given tournament (AC4).
     *
     * <p>Uses H2's {@code MERGE INTO} syntax for an atomic upsert — if a row already exists for the
     * tournament it is replaced; otherwise a new row is inserted. This satisfies AC4 (uploading a
     * new template replaces the existing one) without a separate EXISTS check.
     *
     * <p>E71S02: includes the two nullable ratio columns {@code photo_aspect_ratio_width} and
     * {@code photo_aspect_ratio_height}.
     *
     * @param metadata the template metadata to persist
     */
    @Override
    public void upsert(CertificateTemplateMetadata metadata) {
        String sql =
                """
                MERGE INTO certificate_template (tournament_id, filename, format,
                    upload_timestamp, file_size_bytes,
                    photo_aspect_ratio_width, photo_aspect_ratio_height)
                KEY (tournament_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(
                sql,
                metadata.tournamentId(),
                metadata.filename(),
                metadata.format(),
                Timestamp.from(metadata.uploadedAt()),
                metadata.fileSizeBytes(),
                metadata.photoAspectRatioWidth(),
                metadata.photoAspectRatioHeight());
    }

    // -------------------------------------------------------------------------
    // Delete (AC5)
    // -------------------------------------------------------------------------

    /**
     * Deletes the certificate template metadata row for the given tournament.
     *
     * @param tournamentId the tournament UUID
     * @return {@code true} if a row was deleted; {@code false} if no row existed
     */
    @Override
    public boolean deleteByTournamentId(UUID tournamentId) {
        int rowsAffected =
                jdbcTemplate.update(
                        "DELETE FROM certificate_template WHERE tournament_id = ?", tournamentId);
        return rowsAffected > 0;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private CertificateTemplateMetadata mapRow(ResultSet rs, int rowNum) throws SQLException {
        // photo_aspect_ratio_width / _height are nullable — use getObject to avoid int→0 coercion
        int rawWidth = rs.getInt("photo_aspect_ratio_width");
        Integer ratioWidth = rs.wasNull() ? null : rawWidth;
        int rawHeight = rs.getInt("photo_aspect_ratio_height");
        Integer ratioHeight = rs.wasNull() ? null : rawHeight;

        return new CertificateTemplateMetadata(
                rs.getObject("tournament_id", UUID.class),
                rs.getString("filename"),
                rs.getString("format"),
                rs.getTimestamp("upload_timestamp").toInstant(),
                rs.getLong("file_size_bytes"),
                ratioWidth,
                ratioHeight);
    }
}
