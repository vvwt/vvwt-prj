package de.vvwt.tm.certificate;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for certificate template metadata persistence (E23S07, DEC-35 Item 2).
 *
 * <p>Hand-authored interface wrapping a {@code JdbcTemplate}-based implementation — the concrete
 * class ({@code DefaultCertificateTemplateRepository}) is not a Spring Data repository and does not
 * map to Spring Data CRUD semantics, so this is a separate hand-authored interface per DEC-35 Item
 * 2 (port over adapter pattern for non-Spring-Data repositories).
 *
 * <h2>DEC-35 naming canon</h2>
 *
 * <p>This interface lives in the public Modulith package {@code de.vvwt.tm.certificate.*}. The
 * implementation ({@code DefaultCertificateTemplateRepository}) lives in {@code
 * de.vvwt.tm.certificate.internal.*} per DEC-35 naming canon.
 *
 * <h2>Contract</h2>
 *
 * <p>The three methods correspond to the full public API of the underlying concrete class:
 *
 * <ul>
 *   <li>{@link #findByTournamentId(UUID)} — read path, returns empty if no template stored
 *   <li>{@link #upsert(CertificateTemplateMetadata)} — write path, MERGE semantics (insert or
 *       replace)
 *   <li>{@link #deleteByTournamentId(UUID)} — delete path, returns false if no row existed
 * </ul>
 *
 * @see de.vvwt.tm.certificate.internal.DefaultCertificateTemplateRepository
 * @see CertificateTemplateMetadata
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-35.md">DEC-35</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E23S07.story.md">Story
 *     E23S07 — D-16</a>
 */
public interface CertificateTemplateRepository {

    /**
     * Returns the certificate template metadata for the given tournament, if one exists.
     *
     * @param tournamentId the tournament UUID
     * @return the metadata, or {@link Optional#empty()} if no template is stored
     */
    Optional<CertificateTemplateMetadata> findByTournamentId(UUID tournamentId);

    /**
     * Inserts or replaces the certificate template metadata for the given tournament.
     *
     * <p>Uses H2's {@code MERGE INTO} syntax for an atomic upsert — if a row already exists for the
     * tournament it is replaced; otherwise a new row is inserted.
     *
     * @param metadata the template metadata to persist
     */
    void upsert(CertificateTemplateMetadata metadata);

    /**
     * Deletes the certificate template metadata row for the given tournament.
     *
     * @param tournamentId the tournament UUID
     * @return {@code true} if a row was deleted; {@code false} if no row existed
     */
    boolean deleteByTournamentId(UUID tournamentId);
}
