package de.vvwt.tm.infrastructure.testsupport;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference DAO for Story E16S01 AC9 — demonstrates three-rule DEC-26 compliance.
 *
 * <p>This class is TEST-SCOPE ONLY ({@code src/test/java/}). It is not a production DAO.
 * Its sole purpose is to serve as the subject under test in {@link MarkerDaoTest},
 * showing that {@link TenantDaoTestSupport} makes all three DEC-26 rules the natural
 * default for DAO integration tests.
 *
 * <p>Implementation is intentionally minimal: a single {@code insert} and a single
 * {@code findAll}. This is enough to demonstrate the three-rule pattern end-to-end.
 *
 * @see TenantDaoTestSupport
 * @see MarkerDaoTest
 * @see <a href="../../../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E16S01.story.md">Story E16S01</a>
 */
class MarkerDao {

    private final DataSource dataSource;

    MarkerDao(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        this.dataSource = dataSource;
    }

    /**
     * Inserts a marker row with the given id and note.
     *
     * @param id   primary key (UUID string)
     * @param note text note
     */
    void insert(String id, String note) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO marker (id, note) VALUES (?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, note);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("MarkerDao.insert failed: " + e.getMessage(), e);
        }
    }

    /**
     * Returns all rows from the marker table.
     *
     * @return list of {@link MarkerRecord}; empty list if no rows
     */
    List<MarkerRecord> findAll() {
        List<MarkerRecord> result = new ArrayList<>();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT id, note FROM marker");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new MarkerRecord(rs.getString("id"), rs.getString("note")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("MarkerDao.findAll failed: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * Immutable value object representing a row in the {@code marker} table.
     */
    record MarkerRecord(String id, String note) {}
}
