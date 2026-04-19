package de.vvwt.tm.auth.internal;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data Access Object for the {@code admin_credentials} table in a per-tenant H2 database.
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li><b>DEC-21</b>: Located in {@code de.vvwt.tm.auth.internal} — implementation-private
 *       to the {@code auth} bounded context. Not exposed via the root
 *       {@code de.vvwt.tm.auth} package.</li>
 *   <li><b>DEC-22</b>: Written test-first per the TDD Iron Law. See
 *       {@code AdminCredentialsDaoTest} for the RED commit that preceded this GREEN
 *       implementation.</li>
 *   <li><b>DEC-20</b>: Receives a per-tenant {@link DataSource} resolved by
 *       {@code TenantDataSourceResolver.resolve(tenantId)} in the production path.
 *       The DAO is DataSource-agnostic — it operates against whatever DataSource is
 *       injected, which in production is always a tenant-scoped H2 DataSource.</li>
 * </ul>
 *
 * <h2>Hash storage contract (AC6)</h2>
 * <p>This DAO stores and retrieves credential strings <em>verbatim</em>. It performs
 * no hashing. The caller (e.g., {@code AdminCredentialsBootstrap} in E15S03) is
 * responsible for encoding the plaintext password before passing the hash to
 * {@link #insertNew(UUID, String)}.
 *
 * <h2>Concurrent-start race (AC3)</h2>
 * <p>If two JVM instances start simultaneously and both see an empty table, one INSERT
 * will succeed and the other will fail with {@link DataIntegrityViolationException} due
 * to the {@code idx_admin_credentials_singleton} unique index on {@code singleton_guard}.
 * The caller (E15S03) is responsible for catching this exception and re-querying via
 * {@link #findExisting()}.
 *
 * <h2>Schema dependency</h2>
 * <p>This DAO requires the {@code admin_credentials} table to exist in the target DataSource.
 * The table is created by {@code db/migration/auth/V1__admin_credentials.sql} (E15S05).
 * If the table is absent, {@link #findExisting()} throws an {@link IllegalStateException}
 * with "admin_credentials" in the message (AC5).
 *
 * @see AdminCredentialsDaoTest
 * @since E15S02
 */
public final class AdminCredentialsDao {

    private static final String SELECT_CREDENTIALS =
            "SELECT id, password_hash FROM admin_credentials";

    private static final String INSERT_CREDENTIALS =
            "INSERT INTO admin_credentials (id, password_hash, created_at, singleton_guard) "
            + "VALUES (?, ?, CURRENT_TIMESTAMP, TRUE)";

    private final JdbcTemplate jdbcTemplate;

    /**
     * Constructs an {@code AdminCredentialsDao} backed by the given {@link DataSource}.
     *
     * <p>In production, the caller passes a per-tenant DataSource resolved via
     * {@code TenantDataSourceResolver.resolve(tenantId)} (DEC-20 compliance).
     *
     * @param dataSource the DataSource to use for all JDBC operations; must not be {@code null}
     * @throws IllegalArgumentException if {@code dataSource} is {@code null}
     */
    public AdminCredentialsDao(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException(
                    "dataSource must not be null — pass a per-tenant DataSource resolved "
                    + "via TenantDataSourceResolver (DEC-20)");
        }
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Queries the {@code admin_credentials} table for the single existing row.
     *
     * <p>Returns {@link Optional#empty()} if the table is empty (no credentials have been
     * inserted yet). Returns a {@link CredentialRecord} if one row is present.
     *
     * <h2>Schema-missing behaviour (AC5)</h2>
     * <p>If the {@code admin_credentials} table does not exist in the target DataSource
     * (i.e., E15S05 migration has not been applied to this tenant's DB), this method
     * throws {@link IllegalStateException} with a message containing "admin_credentials".
     *
     * @return {@link Optional} containing the existing credential row, or {@link Optional#empty()}
     *         if no row exists
     * @throws IllegalStateException if the {@code admin_credentials} table is absent from
     *                                the target DataSource (AC5 — missing schema)
     * @throws DataAccessException   for other JDBC errors (propagated as-is)
     */
    public Optional<CredentialRecord> findExisting() {
        try {
            List<CredentialRecord> rows = jdbcTemplate.query(
                    SELECT_CREDENTIALS,
                    (rs, rowNum) -> new CredentialRecord(
                            rs.getObject(1, UUID.class),
                            rs.getString(2)));
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(rows.get(0));
        } catch (DataAccessException e) {
            String message = e.getMessage();
            if (message != null
                    && (message.contains("ADMIN_CREDENTIALS") || message.contains("admin_credentials")
                        || message.contains("Table") || message.contains("table"))) {
                throw new IllegalStateException(
                        "admin_credentials table does not exist in the target DataSource. "
                        + "Ensure the E15S05 Flyway migration (db/migration/auth/V1__admin_credentials.sql) "
                        + "has been applied to this tenant's database before invoking AdminCredentialsDao.",
                        e);
            }
            throw e;
        }
    }

    /**
     * Inserts a new row into {@code admin_credentials}.
     *
     * <h2>Hash storage contract (AC6)</h2>
     * <p>The {@code passwordHash} parameter is stored verbatim — this DAO performs no
     * hashing. The caller must supply an already-encoded hash (e.g., produced by
     * Spring Security's {@code PasswordEncoder}).
     *
     * <h2>Concurrent-start race (AC3)</h2>
     * <p>If a concurrent process has already inserted a row, the unique constraint on
     * {@code singleton_guard} will cause this INSERT to fail with
     * {@link DataIntegrityViolationException}. The caller must catch this exception
     * and invoke {@link #findExisting()} to load the winning process's row.
     *
     * @param id           the UUID to use as the primary key; must not be {@code null}
     * @param passwordHash the already-hashed credential string; must not be {@code null}.
     *                     Stored verbatim — no further encoding is applied.
     * @throws DataIntegrityViolationException if a row already exists (singleton_guard
     *                                         unique constraint violation — concurrent-start
     *                                         race path, AC3)
     * @throws DataAccessException             for other JDBC errors
     */
    public void insertNew(UUID id, String passwordHash) {
        jdbcTemplate.update(INSERT_CREDENTIALS, id, passwordHash);
    }

    // -------------------------------------------------------------------------
    // Value type
    // -------------------------------------------------------------------------

    /**
     * Immutable DTO for a row read from {@code admin_credentials}.
     *
     * @param id           the UUID primary key of the credential row
     * @param passwordHash the stored credential string (bcrypt hash in production; verbatim
     *                     as supplied to {@link #insertNew(UUID, String)})
     */
    public record CredentialRecord(UUID id, String passwordHash) {}
}
