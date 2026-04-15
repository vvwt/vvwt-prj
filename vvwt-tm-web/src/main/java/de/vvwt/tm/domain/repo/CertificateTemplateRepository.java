package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.certificate.CertificateTemplateMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC repository for certificate template metadata (E12S04).
 *
 * <p>Uses {@link JdbcTemplate} directly because the certificate_template table has
 * {@code tournament_id} as its sole primary key — one template per tournament, so there is
 * no entity graph to map and no Spring Data JDBC aggregate to manage. A simple upsert
 * (MERGE statement) is more natural here than the INSERT/UPDATE decision in
 * {@link TenantScopedRepository}.
 *
 * <h2>Tenant scoping (AC8, DEC-5, DEC-17)</h2>
 * <p>Tenant isolation is enforced at the service layer via
 * {@link TournamentRepository#findById(Object)}, which returns empty if the tournament does
 * not exist or belongs to a different tenant. This repository itself only operates on the
 * {@code tournament_id} FK — it trusts that the caller has validated tournament ownership.
 *
 * <h2>DEC-14</h2>
 * <p>H2 embedded; schema managed by Flyway migration V15.
 *
 * @see de.vvwt.tm.domain.certificate.CertificateTemplateServiceImpl
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story E12S04</a>
 */
@Repository
public class CertificateTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    public CertificateTemplateRepository(JdbcTemplate jdbcTemplate) {
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
    public Optional<CertificateTemplateMetadata> findByTournamentId(UUID tournamentId) {
        String sql = """
                SELECT tournament_id, filename, format, upload_timestamp, file_size_bytes
                FROM certificate_template
                WHERE tournament_id = ?
                """;
        List<CertificateTemplateMetadata> results = jdbcTemplate.query(
                sql, this::mapRow, tournamentId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // -------------------------------------------------------------------------
    // Write (upsert — AC1, AC4)
    // -------------------------------------------------------------------------

    /**
     * Inserts or replaces the certificate template metadata for the given tournament (AC4).
     *
     * <p>Uses H2's {@code MERGE INTO} syntax for an atomic upsert — if a row already exists
     * for the tournament it is replaced; otherwise a new row is inserted. This satisfies AC4
     * (uploading a new template replaces the existing one) without a separate EXISTS check.
     *
     * @param metadata the template metadata to persist
     */
    public void upsert(CertificateTemplateMetadata metadata) {
        String sql = """
                MERGE INTO certificate_template (tournament_id, filename, format,
                    upload_timestamp, file_size_bytes)
                KEY (tournament_id)
                VALUES (?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                metadata.tournamentId(),
                metadata.filename(),
                metadata.format(),
                Timestamp.from(metadata.uploadedAt()),
                metadata.fileSizeBytes());
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
    public boolean deleteByTournamentId(UUID tournamentId) {
        int rowsAffected = jdbcTemplate.update(
                "DELETE FROM certificate_template WHERE tournament_id = ?",
                tournamentId);
        return rowsAffected > 0;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private CertificateTemplateMetadata mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CertificateTemplateMetadata(
                rs.getObject("tournament_id", UUID.class),
                rs.getString("filename"),
                rs.getString("format"),
                rs.getTimestamp("upload_timestamp").toInstant(),
                rs.getLong("file_size_bytes"));
    }
}
