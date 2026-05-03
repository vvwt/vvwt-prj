package de.vvwt.tm.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.certificate.internal.DefaultCertificateTemplateRepository;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DAO integration test for {@link DefaultCertificateTemplateRepository} tested via the public
 * {@link CertificateTemplateRepository} interface port (E23S07).
 *
 * <p>Authored via TDD RED-first (DEC-22 Iron Law): this file was committed BEFORE {@link
 * CertificateTemplateRepository} interface existed. See git history for the RED commit.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> loaded via {@link
 *       TenantDaoTestSupport#applyTournamentSchema(DataSource)} (for tournament FK) + {@link
 *       TenantDaoTestSupport#applyMigration} for {@code
 *       db/migration/certificate/V1__initial_schema.sql}.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> write-path assertions use assertj-db
 *       ({@link Table}) via {@link TenantDaoTestSupport#assertDbOf(DataSource)} — never the DAO's
 *       own read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> read-path fixtures inserted via {@link
 *       TenantDaoTestSupport#insertDirectly(DataSource, String, Map)}, not by calling {@code
 *       upsert()} on the DAO under test.
 * </ol>
 *
 * <h2>DEC-36 cross-package typing</h2>
 *
 * <p>This test class is in package {@code de.vvwt.tm.certificate} (public package), while the
 * implementation lives in {@code de.vvwt.tm.certificate.internal}. Per DEC-36, the field {@link
 * #repository} is declared as the public interface type {@link CertificateTemplateRepository} —
 * never as {@link DefaultCertificateTemplateRepository}.
 *
 * <h2>DEC-41 observable-form assertions</h2>
 *
 * <p>Assertions follow the observable-form classification: assertj-db table-level checks are
 * external-spec-citation form (schema from migration); round-trip invariants verify that upsert →
 * direct-JDBC read returns input values preserved.
 *
 * @see CertificateTemplateRepository
 * @see DefaultCertificateTemplateRepository
 * @see TenantDaoTestSupport
 */
@DisplayName("DefaultCertificateTemplateRepository DAO integration tests — E23S07")
class DefaultCertificateTemplateRepositoryIT {

    private static final String TABLE = "certificate_template";

    /** DEC-36: field typed as the public interface, NOT the concrete impl. */
    private CertificateTemplateRepository repository;

    private DataSource dataSource;
    private AssertDbConnection assertDb;

    /** Fixture location ID — inserted into locations table for tournament.location_id FK. */
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Fixture tournament ID — FK for certificate_template.tournament_id. */
    private static final UUID TOURNAMENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        // DEC-26 Rule 1: schema from production migrations
        dataSource = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(dataSource);
        TenantDaoTestSupport.applyMigration(
                dataSource, "db/migration/certificate/V1__initial_schema.sql");

        // DEC-26 Rule 2: independent assertj-db verifier
        assertDb = TenantDaoTestSupport.assertDbOf(dataSource);

        // E45S06: tenant_id removed (DEC-39 D1); tournament requires location_id (DEC-39 D2)
        // Insert FK fixture rows (locations + tournament) for certificate_template FK constraints
        insertLocationFixture();
        insertTournamentFixture();

        // DEC-36: typed as interface
        repository = new DefaultCertificateTemplateRepository(new JdbcTemplate(dataSource));
    }

    // -------------------------------------------------------------------------
    // Write-path tests (DEC-26 Rule 2: assertions via assertj-db, not DAO reads)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("upsert inserts a new row — verified via assertj-db (Rule 2)")
    void upsert_insertsNewRow_verifiedViaAssertjDb() {
        CertificateTemplateMetadata metadata = sampleMetadata(TOURNAMENT_ID);

        repository.upsert(metadata);

        // Rule 2: assertj-db verifies DB state, NOT the DAO's own findByTournamentId
        Table table = assertDb.table(TABLE).build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table)
                .row(0)
                .value("filename")
                .isEqualTo(metadata.filename())
                .value("format")
                .isEqualTo(metadata.format())
                .value("file_size_bytes")
                .isEqualTo(metadata.fileSizeBytes());
    }

    @Test
    @DisplayName(
            "upsert replaces existing row (MERGE semantics) — verified via assertj-db (Rule 2)")
    void upsert_replacesExistingRow_mergeSemantics() {
        CertificateTemplateMetadata first =
                sampleMetadata(TOURNAMENT_ID, "template1.html", "html", 1000L);
        CertificateTemplateMetadata second =
                sampleMetadata(TOURNAMENT_ID, "template2.svg", "svg", 2000L);

        repository.upsert(first);
        repository.upsert(second);

        // Rule 2: only one row should remain — MERGE replaces, not appends
        Table table = assertDb.table(TABLE).build();
        assertThat(table).hasNumberOfRows(1);
        assertThat(table)
                .row(0)
                .value("filename")
                .isEqualTo("template2.svg")
                .value("format")
                .isEqualTo("svg")
                .value("file_size_bytes")
                .isEqualTo(2000L);
    }

    // -------------------------------------------------------------------------
    // Read-path tests (DEC-26 Rule 3: fixtures via insertDirectly, not DAO writes)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findByTournamentId returns metadata when row exists (Rule 3 fixture)")
    void findByTournamentId_returnsMetadata_whenRowExists() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        insertCertificateTemplateFixture(TOURNAMENT_ID, "template.html", "html", now, 512L);

        // Read-path test: interface method
        Optional<CertificateTemplateMetadata> result = repository.findByTournamentId(TOURNAMENT_ID);

        // Observable-form: round-trip invariant — inserted values preserved
        assertThat(result).isPresent();
        CertificateTemplateMetadata found = result.get();
        assertThat(found.tournamentId()).isEqualTo(TOURNAMENT_ID);
        assertThat(found.filename()).isEqualTo("template.html");
        assertThat(found.format()).isEqualTo("html");
        assertThat(found.fileSizeBytes()).isEqualTo(512L);
    }

    @Test
    @DisplayName("findByTournamentId returns empty when no row exists")
    void findByTournamentId_returnsEmpty_whenNoRow() {
        UUID unknownId = UUID.randomUUID();

        Optional<CertificateTemplateMetadata> result = repository.findByTournamentId(unknownId);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Delete tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "deleteByTournamentId returns true and removes row (Rule 3 fixture + Rule 2 verify)")
    void deleteByTournamentId_removesRow_returnsTrue() {
        insertCertificateTemplateFixture(TOURNAMENT_ID, "template.svg", "svg", Instant.now(), 256L);

        boolean deleted = repository.deleteByTournamentId(TOURNAMENT_ID);

        assertThat(deleted).isTrue();
        // Rule 2: assertj-db verifies row is gone
        Table table = assertDb.table(TABLE).build();
        assertThat(table).hasNumberOfRows(0);
    }

    @Test
    @DisplayName("deleteByTournamentId returns false when no row exists")
    void deleteByTournamentId_returnsFalse_whenNoRow() {
        UUID unknownId = UUID.randomUUID();

        boolean deleted = repository.deleteByTournamentId(unknownId);

        assertThat(deleted).isFalse();
    }

    // -------------------------------------------------------------------------
    // Error path tests (AC-ERROR-HANDLING-COVERED)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "upsert with unknown tournament_id violates FK constraint — DataAccessException"
                    + " propagated")
    void upsert_unknownTournament_violatesFkConstraint() {
        UUID unknownTournamentId = UUID.randomUUID();
        CertificateTemplateMetadata metadata = sampleMetadata(unknownTournamentId);

        // DAO-level: FK violation results in DataAccessException (Spring translates JDBC
        // constraint)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.upsert(metadata))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // -------------------------------------------------------------------------
    // Private fixture helpers (DEC-26 Rule 3)
    // -------------------------------------------------------------------------

    /** Inserts a minimal locations row to satisfy the tournament.location_id FK. */
    private void insertLocationFixture() {
        // E45S06: tenant_id removed from locations (DEC-50); tournament requires location_id FK
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", LOCATION_ID);
        cols.put("display_name", "Test Location");
        TenantDaoTestSupport.insertDirectly(dataSource, "locations", cols);
    }

    /** Inserts a minimal tournament row to satisfy the FK on certificate_template. */
    private void insertTournamentFixture() {
        // E45S06: tenant_id removed; location_id NOT NULL (DEC-39 D1/D2)
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", TOURNAMENT_ID);
        cols.put("location_id", LOCATION_ID);
        cols.put("description", "Test Tournament");
        cols.put("match_format", "SETS");
        cols.put("scoring_rule_id", "default");
        cols.put("set_validation_rule_id", "default");
        cols.put("match_generator_id", "default");
        cols.put("status", "DRAFT");
        TenantDaoTestSupport.insertDirectly(dataSource, "tournament", cols);
    }

    /**
     * Inserts a certificate_template row directly via JDBC — DEC-26 Rule 3 fixture. Used by
     * read-path tests so they don't depend on the DAO's own {@code upsert()}.
     */
    private void insertCertificateTemplateFixture(
            UUID tournamentId,
            String filename,
            String format,
            Instant uploadedAt,
            long fileSizeBytes) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("tournament_id", tournamentId);
        cols.put("filename", filename);
        cols.put("format", format);
        cols.put("upload_timestamp", java.sql.Timestamp.from(uploadedAt));
        cols.put("file_size_bytes", fileSizeBytes);
        TenantDaoTestSupport.insertDirectly(dataSource, TABLE, cols);
    }

    private static CertificateTemplateMetadata sampleMetadata(UUID tournamentId) {
        return sampleMetadata(tournamentId, "template.html", "html", 1024L);
    }

    private static CertificateTemplateMetadata sampleMetadata(
            UUID tournamentId, String filename, String format, long fileSizeBytes) {
        return new CertificateTemplateMetadata(
                tournamentId, filename, format, Instant.now(), fileSizeBytes);
    }
}
