// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.db.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Integration tests for {@link AdminCredentialsDao}.
 *
 * <p>Uses a real in-memory H2 DataSource — {@code JdbcTemplate} is NOT mocked (AC2, DEC-22). Each
 * test receives a fresh DataSource via {@link #createFreshDataSource()} to guarantee full isolation
 * between test cases.
 *
 * <h2>Schema source-of-truth</h2>
 *
 * <p>The schema is loaded from the <b>production Flyway migration</b> ({@code
 * db/migration/auth/V1__admin_credentials.sql}) via Spring's {@code ScriptUtils}. This eliminates
 * DDL drift between test and production — whatever column types, constraints, and indexes
 * production uses, the test uses the same bytes.
 *
 * <h2>No Spring application context (by design)</h2>
 *
 * <p>The test deliberately avoids {@code @SpringBootTest} / {@code @JdbcTest} so the DAO is
 * exercised against a bare H2 DataSource with minimal test overhead. This rules out {@code @Sql}
 * (which requires a context) — the equivalent is {@code ScriptUtils} against a raw JDBC {@link
 * java.sql.Connection}.
 *
 * <h2>Independent database-state verification</h2>
 *
 * <p>Assertions about persisted state use <b>assertj-db</b> ({@link Table} against the DataSource)
 * — not the DAO's own query methods. The DAO must not be the evaluator of its own write path:
 * calling {@code dao.insertNew(…)} followed by {@code dao.findExisting()} only proves internal
 * consistency, not that a row reached the database.
 *
 * <h2>DEC-20 compliance</h2>
 *
 * <p>In production, {@code AdminCredentialsDao} receives a {@link DataSource} resolved by {@code
 * TenantDataSourceResolver.resolve(tenantId)} — the per-tenant H2 DataSource. Tests simulate this
 * by providing a real H2 DataSource directly. The DAO contract is DataSource-agnostic; the
 * production wiring passes a tenant-scoped DataSource.
 *
 * <h2>TDD cycle (DEC-22)</h2>
 *
 * <p>This file was committed BEFORE {@link AdminCredentialsDao} existed (RED state: compilation
 * error). See git history for the RED commit preceding the GREEN implementation.
 *
 * @see AdminCredentialsDao
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S02.story.md">Story
 *     E15S02</a>
 */
class AdminCredentialsDaoTest {

    private static final String SCHEMA_MIGRATION = "db/migration/auth/V1__admin_credentials.sql";
    private static final String TABLE = "admin_credentials";

    private DataSource dataSource;
    private AssertDbConnection assertDb;
    private AdminCredentialsDao dao;

    /**
     * Creates a fresh in-memory H2 DataSource for each test — guarantees isolation. Uses a unique
     * DB name per test instance to avoid H2 connection-pool reuse.
     */
    @BeforeEach
    void setUp() {
        dataSource = createFreshDataSource();
        applySchema(dataSource);
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        dao = new AdminCredentialsDao(dataSource);
    }

    /**
     * Builds an assertj-db {@link Table} handle for the {@code admin_credentials} table, reading
     * through the same DataSource as the DAO under test.
     */
    private Table credentialsTable() {
        return assertDb.table(TABLE).build();
    }

    private static DataSource createFreshDataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:admin-cred-test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * Applies the production Flyway migration to the given DataSource — identical DDL bytes to what
     * production applies, no test-only schema variant.
     */
    private static void applySchema(DataSource ds) {
        try (var conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource(SCHEMA_MIGRATION));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to apply production migration "
                            + SCHEMA_MIGRATION
                            + " to test DataSource",
                    e);
        }
    }

    // -------------------------------------------------------------------------
    // T1 — findExisting: empty table returns Optional.empty()
    // -------------------------------------------------------------------------

    /**
     * T1 (base): Empty {@code admin_credentials} table → {@link AdminCredentialsDao#findExisting()}
     * returns {@link Optional#empty()}. Independent verification: the table has 0 rows.
     */
    @Test
    void findExisting_emptyTable_returnsEmpty() {
        Table table = credentialsTable();
        assertThat(table)
                .as("Precondition: freshly migrated table must have 0 rows")
                .hasNumberOfRows(0);

        Optional<AdminCredentialsDao.CredentialRecord> result = dao.findExisting();

        assertThat(result)
                .as("findExisting() on empty table must return Optional.empty()")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // T2 — insertNew: row is actually persisted in the database
    // -------------------------------------------------------------------------

    /**
     * T2 (base): {@link AdminCredentialsDao#insertNew(UUID, String)} actually writes a row to the
     * database. Verified independently via assertj-db against the DataSource — not via the DAO's
     * own {@code findExisting()} (which would be circular).
     */
    @Test
    void insertNew_emptyTable_rowIsPersistedToDatabase() {
        UUID id = UUID.randomUUID();
        String hash = "$2a$10$testhashXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";

        dao.insertNew(id, hash);

        Table table = credentialsTable();
        assertThat(table)
                .as("insertNew must persist exactly one row to admin_credentials")
                .hasNumberOfRows(1)
                .row(0)
                .value("id")
                .isEqualTo(id)
                .value("password_hash")
                .isEqualTo(hash)
                .value("singleton_guard")
                .isEqualTo(true);
    }

    // -------------------------------------------------------------------------
    // T3 — findExisting reads a pre-existing row (decoupled from insertNew)
    // -------------------------------------------------------------------------

    /**
     * T3 (base): {@link AdminCredentialsDao#findExisting()} correctly reads a row that was inserted
     * by <b>direct SQL</b> (not by the DAO). This decouples the read-path test from the write-path
     * test: findExisting is verified independently of insertNew.
     */
    @Test
    void findExisting_withRowInsertedDirectly_returnsCredentialRecord() {
        UUID expectedId = UUID.randomUUID();
        String expectedHash = "$2a$10$testhashXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
        insertRowDirectly(expectedId, expectedHash);

        AdminCredentialsDao.CredentialRecord record = dao.findExisting().orElseThrow();

        assertThat(record.id())
                .as("Returned record id must match the directly inserted UUID")
                .isEqualTo(expectedId);
        assertThat(record.passwordHash())
                .as("Returned record passwordHash must match the directly inserted hash")
                .isEqualTo(expectedHash);
    }

    // -------------------------------------------------------------------------
    // T4 — AC6: hash stored verbatim (no hashing by DAO)
    // -------------------------------------------------------------------------

    /**
     * T4 (AC6): The DAO stores the supplied string verbatim — it performs no hashing. Verified
     * against the database column directly (not via findExisting).
     *
     * <p>Hashing is an orchestration concern (handled by {@code AdminCredentialsBootstrap} in
     * E15S03 using Spring Security's {@code PasswordEncoder}).
     */
    @Test
    void insertNew_storesHashVerbatim_withoutModification() {
        UUID id = UUID.randomUUID();
        String arbitraryString = "not-a-real-bcrypt-hash-just-a-test-sentinel";

        dao.insertNew(id, arbitraryString);

        Table table = credentialsTable();
        assertThat(table)
                .as("AC6: DAO must store the supplied string verbatim in the password_hash column")
                .row(0)
                .value("password_hash")
                .isEqualTo(arbitraryString);
    }

    // -------------------------------------------------------------------------
    // T5 — AC3: second insert raises DataIntegrityViolationException (singleton guard)
    // -------------------------------------------------------------------------

    /**
     * T5 (AC3 — singleton guard): Inserting a second row while the table already has one must throw
     * {@link DataIntegrityViolationException} due to the {@code idx_admin_credentials_singleton}
     * unique index on {@code singleton_guard}.
     */
    @Test
    void insertNew_secondAttempt_throwsDataIntegrityViolationException() {
        dao.insertNew(UUID.randomUUID(), "hash-first");

        assertThatThrownBy(() -> dao.insertNew(UUID.randomUUID(), "hash-second"))
                .as(
                        "AC3: second insert must fail with DataIntegrityViolationException "
                                + "(singleton_guard unique constraint)")
                .isInstanceOf(DataIntegrityViolationException.class);

        Table table = credentialsTable();
        assertThat(table)
                .as(
                        "AC3: after a rejected second insert, the table must still contain exactly"
                                + " one row")
                .hasNumberOfRows(1);
    }

    // -------------------------------------------------------------------------
    // T6 — AC3: concurrent-start race — exactly one winner, loser gets exception
    // -------------------------------------------------------------------------

    /**
     * T6 (AC3 — concurrent race): Two threads attempt to insert simultaneously. One succeeds; the
     * other catches {@link DataIntegrityViolationException}. Final state (verified via assertj-db):
     * exactly one row in the table.
     *
     * <p>Implementation: a {@link CyclicBarrier} synchronizes both threads at the point just before
     * they call {@code insertNew()} so that both see an empty table before either inserts. The test
     * is deterministic about the final invariant (exactly one row) rather than about which thread
     * wins.
     *
     * <p>H2 in-memory databases in the same JVM with {@code ;DB_CLOSE_DELAY=-1} support concurrent
     * connections. The unique constraint on {@code singleton_guard} enforces the single-row
     * invariant at the database level.
     */
    @Test
    void concurrentInsert_racePath_exactlyOneRowAndLoserGetsException()
            throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Exception> thread1Exception = new AtomicReference<>();
        AtomicReference<Exception> thread2Exception = new AtomicReference<>();

        AdminCredentialsDao sharedDao = new AdminCredentialsDao(dataSource);

        Runnable insertTask1 =
                () -> {
                    try {
                        barrier.await();
                        sharedDao.insertNew(UUID.randomUUID(), "hash-from-thread-1");
                    } catch (Exception e) {
                        thread1Exception.set(e);
                    }
                };

        Runnable insertTask2 =
                () -> {
                    try {
                        barrier.await();
                        sharedDao.insertNew(UUID.randomUUID(), "hash-from-thread-2");
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

        boolean t1Won = thread1Exception.get() == null;
        boolean t2Won = thread2Exception.get() == null;

        assertThat(t1Won ^ t2Won)
                .as(
                        "AC3: exactly one thread must win (no exception) and one must lose "
                                + "(DataIntegrityViolationException). Both won="
                                + t1Won
                                + ", Both lost="
                                + (!t1Won && !t2Won))
                .isTrue();

        Exception loserException = t1Won ? thread2Exception.get() : thread1Exception.get();
        assertThat(loserException)
                .as("AC3: the losing thread must get a DataIntegrityViolationException")
                .isInstanceOf(DataIntegrityViolationException.class);

        Table table = credentialsTable();
        assertThat(table)
                .as("AC3: after concurrent inserts, exactly one row must be in admin_credentials")
                .hasNumberOfRows(1);
    }

    // -------------------------------------------------------------------------
    // T7 — AC3: post-race findExisting returns the winner's row
    // -------------------------------------------------------------------------

    /**
     * T7 (AC3 — post-race re-query): After a simulated race (first insert wins), the database still
     * contains exactly the winner's row (independently verified), and a subsequent {@code
     * findExisting()} returns it.
     */
    @Test
    void afterRace_findExisting_returnsWinnersRow() {
        UUID winnerId = UUID.randomUUID();
        String winnerHash = "winner-hash-value";

        dao.insertNew(winnerId, winnerHash);

        try {
            dao.insertNew(UUID.randomUUID(), "loser-hash");
        } catch (DataIntegrityViolationException ignored) {
            // Expected — loser's INSERT fails
        }

        Table table = credentialsTable();
        assertThat(table)
                .as(
                        "AC3: after the loser's failed INSERT, the database must still hold "
                                + "exactly the winner's row, unmodified")
                .hasNumberOfRows(1)
                .row(0)
                .value("id")
                .isEqualTo(winnerId)
                .value("password_hash")
                .isEqualTo(winnerHash);

        AdminCredentialsDao.CredentialRecord record =
                dao.findExisting()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Post-race findExisting must return a record — the"
                                                        + " winner's row must survive the loser's"
                                                        + " failed INSERT"));

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
     * T8 (AC5 — missing schema): Invoking {@code findExisting()} against a DataSource whose DB has
     * NOT had the {@code admin_credentials} table created must throw an exception whose message
     * contains "admin_credentials" — not a cryptic JDBC error.
     *
     * <p>This simulates the AC5 scenario: E15S05 migration not yet applied.
     */
    @Test
    void missingSchema_findExisting_throwsDescriptiveException() {
        DataSource noSchemaDs = createFreshDataSource();
        AdminCredentialsDao daoWithoutSchema = new AdminCredentialsDao(noSchemaDs);

        assertThatThrownBy(daoWithoutSchema::findExisting)
                .as(
                        "AC5: findExisting() against missing schema must throw a descriptive"
                                + " exception with 'admin_credentials' in the message")
                .hasMessageContaining("admin_credentials");
    }

    // -------------------------------------------------------------------------
    // T9 — constructor validation: null DataSource throws IAE
    // -------------------------------------------------------------------------

    /**
     * T9 (validation): Passing {@code null} as the {@link DataSource} to the constructor must throw
     * {@link IllegalArgumentException} with "dataSource" in the message.
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
     * T10 (AC7 — package placement): {@link AdminCredentialsDao} must be in {@code
     * de.vvwt.tm.auth.internal} and must NOT be in the root {@code de.vvwt.tm.auth} package.
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
     * T11 (AC8 — Modulith verify): {@code ApplicationModules.verify()} must remain green after this
     * story. New code is in {@code de.vvwt.tm.auth.internal} and only imports from JDK, Spring
     * JDBC, and optionally {@code tenant::api}.
     */
    @Test
    void applicationModulesVerify_remainsGreen() {
        org.springframework.modulith.core.ApplicationModules.of(
                        de.vvwt.tm.TournamentManagerApplication.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // Helper — direct JDBC insert, used to decouple read-path tests from the DAO's write path
    // -------------------------------------------------------------------------

    /**
     * Inserts a credential row using plain JDBC (bypassing {@link AdminCredentialsDao}). Used by
     * read-path tests so that the row under test is known to exist in the database without relying
     * on the DAO's own write method to have worked correctly.
     */
    private void insertRowDirectly(UUID id, String passwordHash) {
        String sql =
                "INSERT INTO "
                        + TABLE
                        + " (id, password_hash, singleton_guard) VALUES (?, ?, TRUE)";
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, passwordHash);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to insert admin_credentials row directly", e);
        }
    }
}
