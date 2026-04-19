package de.vvwt.tm.auth.internal;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link AdminCredentialsDao}.
 *
 * <p>Uses a real in-memory H2 DataSource — {@code JdbcTemplate} is NOT mocked (AC2, DEC-22).
 * Each test receives a fresh DataSource via {@link #createFreshDataSource()} to guarantee
 * full isolation between test cases.
 *
 * <h2>Schema fixture</h2>
 * <p>E15S05 (Flyway migration for {@code db/migration/auth/V1__admin_credentials.sql}) has not
 * yet been delivered. Tests that require the {@code admin_credentials} table apply the inline
 * SQL fixture in {@link #applySchema(DataSource)} as a scaffold. This fixture will be removed
 * once E15S05 lands (the production Flyway runner will apply the migration instead).
 *
 * <h2>DEC-20 compliance</h2>
 * <p>In production, {@code AdminCredentialsDao} receives a {@link DataSource} resolved by
 * {@code TenantDataSourceResolver.resolve(tenantId)} — the per-tenant H2 DataSource.
 * Tests simulate this by providing a real H2 DataSource directly. The DAO contract is
 * DataSource-agnostic; the production wiring passes a tenant-scoped DataSource.
 *
 * <h2>TDD cycle (DEC-22)</h2>
 * <p>This file was committed BEFORE {@link AdminCredentialsDao} existed (RED state:
 * compilation error). See git history for the RED commit preceding the GREEN implementation.
 *
 * @see AdminCredentialsDao
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S02.story.md">Story E15S02</a>
 */
class AdminCredentialsDaoTest {

    // -------------------------------------------------------------------------
    // Inline schema fixture (E15S05 scaffold — see class Javadoc)
    // -------------------------------------------------------------------------

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS admin_credentials ("
            + "id              UUID          NOT NULL, "
            + "password_hash   VARCHAR(255)  NOT NULL, "
            + "created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "singleton_guard BOOLEAN       NOT NULL DEFAULT TRUE, "
            + "CONSTRAINT pk_admin_credentials PRIMARY KEY (id), "
            + "CONSTRAINT chk_singleton_guard CHECK (singleton_guard = TRUE)"
            + ")";

    private static final String CREATE_INDEX_SQL =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_credentials_singleton "
            + "ON admin_credentials (singleton_guard)";

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private DataSource dataSource;
    private AdminCredentialsDao dao;

    /**
     * Creates a fresh in-memory H2 DataSource for each test — guarantees isolation.
     * Uses a unique DB name per test instance to avoid H2 connection-pool reuse.
     */
    @BeforeEach
    void setUp() {
        dataSource = createFreshDataSource();
        applySchema(dataSource);
        dao = new AdminCredentialsDao(dataSource);
    }

    private static DataSource createFreshDataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        // Unique in-memory DB name per test instance to prevent cross-test state leakage
        ds.setURL("jdbc:h2:mem:admin-cred-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static void applySchema(DataSource ds) {
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            stmt.execute(CREATE_INDEX_SQL);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply inline admin_credentials schema fixture", e);
        }
    }

    // -------------------------------------------------------------------------
    // T1 — findExisting: empty table returns Optional.empty()
    // -------------------------------------------------------------------------

    /**
     * T1 (base): Empty {@code admin_credentials} table → {@link AdminCredentialsDao#findExisting()}
     * returns {@link Optional#empty()}.
     */
    @Test
    void findExisting_emptyTable_returnsEmpty() {
        Optional<AdminCredentialsDao.CredentialRecord> result = dao.findExisting();

        assertThat(result)
                .as("findExisting() on empty table must return Optional.empty()")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // T2 — insertNew: row is created and findExisting returns it
    // -------------------------------------------------------------------------

    /**
     * T2 (base): {@link AdminCredentialsDao#insertNew(UUID, String)} inserts a row;
     * subsequent {@link AdminCredentialsDao#findExisting()} returns a non-empty Optional.
     */
    @Test
    void insertNew_emptyTable_rowIsCreated() {
        UUID id = UUID.randomUUID();
        dao.insertNew(id, "$2a$10$testhashXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");

        Optional<AdminCredentialsDao.CredentialRecord> result = dao.findExisting();

        assertThat(result)
                .as("findExisting() after insertNew must return a non-empty Optional")
                .isPresent();
    }

    // -------------------------------------------------------------------------
    // T3 — findExisting: returned record has correct id and passwordHash
    // -------------------------------------------------------------------------

    /**
     * T3 (base): The {@link AdminCredentialsDao.CredentialRecord} returned by
     * {@link AdminCredentialsDao#findExisting()} has the correct id and passwordHash.
     */
    @Test
    void findExisting_afterInsert_returnsCredentialRecord() {
        UUID expectedId = UUID.randomUUID();
        String expectedHash = "$2a$10$testhashXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

        dao.insertNew(expectedId, expectedHash);

        AdminCredentialsDao.CredentialRecord record = dao.findExisting().orElseThrow();

        assertThat(record.id())
                .as("Returned record id must match the inserted UUID")
                .isEqualTo(expectedId);

        assertThat(record.passwordHash())
                .as("Returned record passwordHash must match the inserted hash")
                .isEqualTo(expectedHash);
    }

    // -------------------------------------------------------------------------
    // T4 — AC6: hash stored verbatim (no hashing by DAO)
    // -------------------------------------------------------------------------

    /**
     * T4 (AC6): The DAO stores the supplied string verbatim — it performs no hashing.
     * Pass a non-hash sentinel string and verify it is retrieved unchanged.
     *
     * <p>Hashing is an orchestration concern (handled by {@code AdminCredentialsBootstrap}
     * in E15S03 using Spring Security's {@code PasswordEncoder}).
     */
    @Test
    void insertNew_storesHashVerbatim_withoutModification() {
        UUID id = UUID.randomUUID();
        String arbitraryString = "not-a-real-bcrypt-hash-just-a-test-sentinel";

        dao.insertNew(id, arbitraryString);

        String stored = dao.findExisting().orElseThrow().passwordHash();
        assertThat(stored)
                .as("AC6: DAO must store the supplied string verbatim — no hashing performed")
                .isEqualTo(arbitraryString);
    }

    // -------------------------------------------------------------------------
    // T5 — AC3: second insert raises DataIntegrityViolationException (singleton guard)
    // -------------------------------------------------------------------------

    /**
     * T5 (AC3 — singleton guard): Inserting a second row while the table already has one
     * must throw {@link DataIntegrityViolationException} due to the
     * {@code idx_admin_credentials_singleton} unique index on {@code singleton_guard}.
     */
    @Test
    void insertNew_secondAttempt_throwsDataIntegrityViolationException() {
        dao.insertNew(UUID.randomUUID(), "hash-first");

        assertThatThrownBy(() -> dao.insertNew(UUID.randomUUID(), "hash-second"))
                .as("AC3: second insert must fail with DataIntegrityViolationException "
                    + "(singleton_guard unique constraint)")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------------------
    // T6 — AC3: concurrent-start race — exactly one winner, loser gets exception
    // -------------------------------------------------------------------------

    /**
     * T6 (AC3 — concurrent race): Two threads attempt to insert simultaneously.
     * One succeeds; the other catches {@link DataIntegrityViolationException}.
     * Final state: exactly one row in the table.
     *
     * <p>Implementation: a {@link CyclicBarrier} synchronizes both threads at the point
     * just before they call {@code insertNew()} so that both see an empty table before
     * either inserts. The test is deterministic about the final invariant (exactly one row)
     * rather than about which thread wins.
     *
     * <p>H2 in-memory databases in the same JVM with {@code ;DB_CLOSE_DELAY=-1} support
     * concurrent connections. The unique constraint on {@code singleton_guard} enforces
     * the single-row invariant at the database level.
     */
    @Test
    void concurrentInsert_racePath_exactlyOneRowAndLoserGetsException()
            throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Exception> thread1Exception = new AtomicReference<>();
        AtomicReference<Exception> thread2Exception = new AtomicReference<>();

        // Both threads share the same DAO (same DataSource)
        AdminCredentialsDao sharedDao = new AdminCredentialsDao(dataSource);

        Runnable insertTask1 = () -> {
            try {
                barrier.await(); // wait for both threads to be ready
                sharedDao.insertNew(UUID.randomUUID(), "hash-from-thread-1");
            } catch (DataIntegrityViolationException e) {
                thread1Exception.set(e);
            } catch (Exception e) {
                thread1Exception.set(e);
            }
        };

        Runnable insertTask2 = () -> {
            try {
                barrier.await(); // wait for both threads to be ready
                sharedDao.insertNew(UUID.randomUUID(), "hash-from-thread-2");
            } catch (DataIntegrityViolationException e) {
                thread2Exception.set(e);
            } catch (Exception e) {
                thread2Exception.set(e);
            }
        };

        Thread t1 = new Thread(insertTask1, "race-thread-1");
        Thread t2 = new Thread(insertTask2, "race-thread-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Exactly one thread must have won (no exception) and one must have lost (DIVE)
        boolean t1Won = thread1Exception.get() == null;
        boolean t2Won = thread2Exception.get() == null;

        assertThat(t1Won ^ t2Won)
                .as("AC3: exactly one thread must win (no exception) and one must lose "
                    + "(DataIntegrityViolationException). Both won=" + t1Won + ", Both lost="
                    + (!t1Won && !t2Won))
                .isTrue();

        // The losing thread's exception must be a DIVE (singleton_guard constraint)
        Exception loserException = t1Won ? thread2Exception.get() : thread1Exception.get();
        assertThat(loserException)
                .as("AC3: the losing thread must get a DataIntegrityViolationException")
                .isInstanceOf(DataIntegrityViolationException.class);

        // Final state: exactly one row in the table
        long rowCount = countRows(dataSource);
        assertThat(rowCount)
                .as("AC3: after concurrent inserts, exactly one row must be in admin_credentials")
                .isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // T7 — AC3: post-race findExisting returns the winner's row
    // -------------------------------------------------------------------------

    /**
     * T7 (AC3 — post-race re-query): After a simulated race (first insert wins),
     * a subsequent {@code findExisting()} returns the winner's credential record consistently.
     */
    @Test
    void afterRace_findExisting_returnsWinnersRow() {
        UUID winnerId = UUID.randomUUID();
        String winnerHash = "winner-hash-value";

        // Winner inserts first
        dao.insertNew(winnerId, winnerHash);

        // Loser catches the exception (simulated by direct second insertNew call)
        try {
            dao.insertNew(UUID.randomUUID(), "loser-hash");
        } catch (DataIntegrityViolationException ignored) {
            // Expected — loser's INSERT fails
        }

        // Post-race re-query must return the winner's row
        AdminCredentialsDao.CredentialRecord record = dao.findExisting().orElseThrow(
                () -> new AssertionError("Post-race findExisting must return a record — " +
                        "the winner's row must survive the loser's failed INSERT"));

        assertThat(record.id())
                .as("AC3: post-race re-query must return the winner's id")
                .isEqualTo(winnerId);

        assertThat(record.passwordHash())
                .as("AC3: post-race re-query must return the winner's hash verbatim")
                .isEqualTo(winnerHash);
    }

    // -------------------------------------------------------------------------
    // T8 — AC5: missing schema raises a descriptive exception
    // -------------------------------------------------------------------------

    /**
     * T8 (AC5 — missing schema): Invoking {@code findExisting()} against a DataSource
     * whose DB has NOT had the {@code admin_credentials} table created must throw an exception
     * whose message contains "admin_credentials" — not a cryptic JDBC error.
     *
     * <p>This simulates the AC5 scenario: E15S05 migration not yet applied.
     */
    @Test
    void missingSchema_findExisting_throwsDescriptiveException() {
        // Create a fresh DataSource WITHOUT applying the schema fixture
        DataSource noSchemaDs = createFreshDataSource();
        AdminCredentialsDao daoWithoutSchema = new AdminCredentialsDao(noSchemaDs);

        assertThatThrownBy(daoWithoutSchema::findExisting)
                .as("AC5: findExisting() against missing schema must throw a descriptive exception "
                    + "with 'admin_credentials' in the message")
                .hasMessageContaining("admin_credentials");
    }

    // -------------------------------------------------------------------------
    // T9 — constructor validation: null DataSource throws IAE
    // -------------------------------------------------------------------------

    /**
     * T9 (validation): Passing {@code null} as the {@link DataSource} to the constructor
     * must throw {@link IllegalArgumentException} with "dataSource" in the message.
     */
    @Test
    void constructor_nullDataSource_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new AdminCredentialsDao(null))
                .as("Constructor must reject null DataSource with IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataSource");
    }

    // -------------------------------------------------------------------------
    // T10 — AC7: package placement
    // -------------------------------------------------------------------------

    /**
     * T10 (AC7 — package placement): {@link AdminCredentialsDao} must be in
     * {@code de.vvwt.tm.auth.internal} and must NOT be in the root
     * {@code de.vvwt.tm.auth} package.
     */
    @Test
    void packagePlacement_daoClass_isInAuthInternal() {
        String packageName = AdminCredentialsDao.class.getPackageName();

        assertThat(packageName)
                .as("AC7: AdminCredentialsDao must be in de.vvwt.tm.auth.internal")
                .isEqualTo("de.vvwt.tm.auth.internal");
    }

    // -------------------------------------------------------------------------
    // T11 — AC8: ApplicationModulesTest still passes
    // -------------------------------------------------------------------------

    /**
     * T11 (AC8 — Modulith verify): {@code ApplicationModules.verify()} must remain green
     * after this story. New code is in {@code de.vvwt.tm.auth.internal} and only imports
     * from JDK, Spring JDBC, and optionally {@code tenant::api}.
     */
    @Test
    void applicationModulesVerify_remainsGreen() {
        org.springframework.modulith.core.ApplicationModules.of(
                de.vvwt.tm.TournamentManagerApplication.class
        ).verify();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static long countRows(DataSource ds) {
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM admin_credentials")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to count admin_credentials rows", e);
        }
    }
}
