package de.vvwt.tm.infrastructure.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reference DAO test demonstrating TDD-first three-rule DEC-26 compliance.
 *
 * <p>This class is the acceptance proof for {@link TenantDaoTestSupport} (Story E16S01 AC9). It is
 * entirely test-scope — {@link MarkerDao} is also a test-scope class, not production code.
 *
 * <h2>Three DEC-26 rules applied (AC9)</h2>
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from production migration:</b> {@code applyMigration} loads the schema
 *       from the SQL file at {@code db/migration/testsupport/marker_schema.sql} (the canonical
 *       schema source for this test-only table).
 *   <li><b>Rule 2 — Independent persistence verifier:</b> {@code insert_*} tests verify that a row
 *       was written to the database via {@code assertDbOf} — NOT via {@code MarkerDao.findAll()}.
 *   <li><b>Rule 3 — Read/write decoupling:</b> {@code findAll_*} tests seed the fixture via {@code
 *       insertDirectly} — NOT via {@code MarkerDao.insert()}.
 * </ol>
 *
 * @see TenantDaoTestSupport
 * @see MarkerDao
 * @see <a
 *     href="../../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E16S01.story.md">Story
 *     E16S01</a>
 */
class MarkerDaoTest {

    private static final String SCHEMA_RESOURCE = "db/migration/testsupport/marker_schema.sql";

    private DataSource dataSource;
    private MarkerDao dao;

    @BeforeEach
    void setUp() {
        dataSource = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyMigration(dataSource, SCHEMA_RESOURCE); // Rule 1
        dao = new MarkerDao(dataSource);
    }

    // =========================================================================
    // M1 — insert: row is verified by independent instrument (Rule 2)
    // =========================================================================

    /**
     * M1 (Rule 2 — independent persistence verifier): {@link MarkerDao#insert(String, String)}
     * writes a row to the database. Verified via {@code TenantDaoTestSupport.assertDbOf} — NOT via
     * {@code dao.findAll()}.
     */
    @Test
    void insert_newMarker_rowAppearsInDatabase() {
        String id = UUID.randomUUID().toString();
        String note = "rule-2-test-marker";

        dao.insert(id, note);

        // Rule 2: verify via assertj-db (not via dao.findAll())
        Table table = TenantDaoTestSupport.assertDbOf(dataSource).table("marker").build();
        assertThat(table)
                .as("insert() must persist exactly one row in marker table (Rule 2)")
                .hasNumberOfRows(1)
                .row(0)
                .value("id")
                .isEqualTo(id)
                .value("note")
                .isEqualTo(note);
    }

    // =========================================================================
    // M2 — findAll: reads only the seeded fixture (Rule 3)
    // =========================================================================

    /**
     * M2 (Rule 3 — read/write decoupling): {@link MarkerDao#findAll()} returns only rows that were
     * seeded via {@code TenantDaoTestSupport.insertDirectly} — NOT via {@code dao.insert()}.
     *
     * <p>This ensures that a broken {@code insert()} cannot mask a broken {@code findAll()}.
     */
    @Test
    void findAll_withDirectlyInsertedRow_returnsRow() {
        String id = UUID.randomUUID().toString();
        String note = "rule-3-test-marker";

        // Rule 3: seed via direct JDBC (not via dao.insert())
        TenantDaoTestSupport.insertDirectly(dataSource, "marker", Map.of("id", id, "note", note));

        List<MarkerDao.MarkerRecord> records = dao.findAll();

        assertThat(records)
                .as("findAll() must return the directly inserted marker row (Rule 3)")
                .hasSize(1);
        assertThat(records.get(0).id())
                .as("findAll() record id must match the directly inserted id")
                .isEqualTo(id);
        assertThat(records.get(0).note())
                .as("findAll() record note must match the directly inserted note")
                .isEqualTo(note);
    }

    // =========================================================================
    // M3 — findAll: empty table returns empty list
    // =========================================================================

    /** M3 (base): Empty table → {@code findAll()} returns an empty list. */
    @Test
    void findAll_emptyTable_returnsEmptyList() {
        List<MarkerDao.MarkerRecord> records = dao.findAll();

        assertThat(records).as("findAll() on empty table must return empty list").isEmpty();
    }
}
