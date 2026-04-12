package de.vvwt.tm.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Bootstrap component that generates or loads the admin password at application startup.
 *
 * <p>Runs as an {@link ApplicationRunner} with {@code @Order(2)} so it executes after
 * {@code DefaultTenantBootstrap} ({@code @Order(1)}) and after Flyway has applied all
 * migrations (including V6 which creates the {@code admin_credentials} table).
 *
 * <h2>Startup flow</h2>
 * <ol>
 *   <li>Query {@code SELECT id, password_hash FROM admin_credentials} (at most one row
 *       due to the singleton_guard unique index from V6 migration).</li>
 *   <li>If one row exists: load the hash into memory. Log the startup notice (AC3).
 *       The plaintext password cannot be recovered from the hash — only the INFO log line
 *       from first boot matters. The log line on subsequent boots reads:
 *       {@code [tm-auth] Admin password: (set at first start — check startup log)}</li>
 *   <li>If no row exists: generate a 16-character alphanumeric password via
 *       {@link SecureRandom} (AC1, AC11). Hash it with bcrypt. Persist both ID and hash.
 *       Log the plaintext password at INFO (AC3). Store the hash in memory.</li>
 *   <li>If more than one row exists: abort with {@link IllegalStateException} — schema
 *       invariant violated (should be impossible due to singleton_guard unique index).</li>
 * </ol>
 *
 * <h2>Concurrent-start race (AC1)</h2>
 * <p>If two processes start simultaneously and both see zero rows, one INSERT will fail
 * with a unique-constraint violation on {@code idx_admin_credentials_singleton}. The
 * losing process catches {@link DataIntegrityViolationException}, re-queries to load the
 * winner's hash, and logs a notice that it could not display the plaintext. The winner
 * already logged the plaintext (AC3 satisfied for the first boot's primary process).
 *
 * <h2>DB unavailable (AC9)</h2>
 * <p>Any DB exception other than the concurrent-race constraint violation is rethrown as
 * {@link IllegalStateException} with a clear actionable message, aborting startup.
 *
 * <h2>Password strength (AC11)</h2>
 * <p>The alphabet has 62 characters [A-Za-z0-9]. A 16-character password drawn uniformly
 * from this alphabet has log2(62^16) ≈ 95.3 bits of entropy — well above the 72-bit
 * minimum required by AC11. {@link SecureRandom} is cryptographically strong per the
 * Java SE specification.
 *
 * <h2>Password not logged at DEBUG/TRACE (AC11)</h2>
 * <p>The plaintext variable is used only in the INFO log line. No DEBUG or TRACE log
 * statement in this class references the plaintext or hash value.
 *
 * @see SecurityConfig
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S02.story.md">Story E05S02</a>
 */
@Component
@Order(2)
public class AdminCredentialsBootstrap implements ApplicationRunner, AdminCredentialsProvider {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialsBootstrap.class);

    /**
     * Password alphabet: uppercase, lowercase, digits — 62 characters.
     * No special characters to ensure log-friendliness and AC11 compliance (alphanumeric).
     */
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** Password length: 16 alphanumeric characters → ~95 bits of entropy (AC11: >= 72 bits). */
    private static final int PASSWORD_LENGTH = 16;

    private static final String SELECT_CREDENTIALS =
            "SELECT id, password_hash FROM admin_credentials";

    private static final String INSERT_CREDENTIALS =
            "INSERT INTO admin_credentials (id, password_hash, created_at, singleton_guard) "
            + "VALUES (?, ?, CURRENT_TIMESTAMP, TRUE)";

    /**
     * Holds the bcrypt hash of the admin password after bootstrap completes.
     * {@code null} before bootstrap — callers must not access before context is ready.
     */
    private volatile String passwordHash;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;

    public AdminCredentialsBootstrap(JdbcTemplate jdbcTemplate,
                                     TransactionTemplate transactionTemplate,
                                     PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.passwordEncoder = passwordEncoder;
        this.secureRandom = new SecureRandom();
    }

    // -------------------------------------------------------------------------
    // ApplicationRunner
    // -------------------------------------------------------------------------

    @Override
    public void run(ApplicationArguments args) {
        List<CredentialRow> existing = queryCredentials();

        if (existing.size() > 1) {
            throw new IllegalStateException(
                    "Multiple admin_credentials rows detected — schema invariant violated "
                    + "(singleton_guard unique index should prevent this). "
                    + "Investigate database integrity. Found " + existing.size() + " rows.");
        }

        if (existing.size() == 1) {
            // AC2: subsequent boots — load existing hash
            this.passwordHash = existing.get(0).passwordHash();
            // AC3: display notice. Plaintext cannot be recovered from the hash on subsequent boots.
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin password: (set at first start — check startup log)");
            log.info("[tm-auth] Username: admin");
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin credentials bootstrap complete — existing credentials loaded");
            return;
        }

        // AC1: first boot — generate, persist, display
        generateAndPersist();
    }

    // -------------------------------------------------------------------------
    // AdminCredentialsProvider
    // -------------------------------------------------------------------------

    /**
     * Returns the bcrypt hash of the admin password for use by {@link SecurityConfig}.
     *
     * @throws IllegalStateException if called before bootstrap completes
     */
    @Override
    public String getPasswordHash() {
        String hash = this.passwordHash;
        if (hash == null) {
            throw new IllegalStateException(
                    "AdminCredentialsProvider.getPasswordHash() called before bootstrap "
                    + "completed. Ensure all callers are Spring beans initialized after "
                    + "AdminCredentialsBootstrap (@Order(2)) has run.");
        }
        return hash;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private List<CredentialRow> queryCredentials() {
        return jdbcTemplate.query(
                SELECT_CREDENTIALS,
                (rs, rowNum) -> new CredentialRow(
                        rs.getObject(1, UUID.class),
                        rs.getString(2)));
    }

    /**
     * Generates a random alphanumeric password, hashes it with bcrypt, persists the hash,
     * and logs the plaintext at INFO level.
     *
     * <p>On concurrent-start race: catches {@link DataIntegrityViolationException}, re-queries,
     * and loads the winning process's hash. Does NOT log the plaintext (cannot recover it).
     */
    private void generateAndPersist() {
        // AC1, AC11: generate cryptographically random password using SecureRandom
        String plaintext = generatePassword();
        String hash = passwordEncoder.encode(plaintext);
        UUID id = UUID.randomUUID();

        try {
            transactionTemplate.executeWithoutResult(status ->
                    jdbcTemplate.update(INSERT_CREDENTIALS, id, hash));

            this.passwordHash = hash;

            // AC3, AC11: log plaintext at INFO only — never at DEBUG or TRACE
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin password: {}", plaintext);
            log.info("[tm-auth] Username: admin");
            log.info("[tm-auth] IMPORTANT: Save this password. It is only shown once.");
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin credentials generated and persisted (first start)");

        } catch (DataIntegrityViolationException race) {
            // AC1: concurrent-start race — another process won the INSERT.
            log.info("[tm-auth] Concurrent start detected: another process inserted admin "
                    + "credentials first. Re-querying. "
                    + "Constraint violation: {}", race.getMessage());

            List<CredentialRow> winners = queryCredentials();
            if (winners.isEmpty()) {
                throw new IllegalStateException(
                        "FATAL: Caught concurrent admin_credentials insertion violation but "
                        + "subsequent re-query found no row. Database is in an inconsistent state. "
                        + "Root cause: " + race.getMessage(), race);
            }
            this.passwordHash = winners.get(0).passwordHash();
            log.info("[tm-auth] Admin credentials loaded from concurrent-start winner "
                    + "(plaintext shown by the primary process — check its startup log)");

        } catch (Exception unexpected) {
            // AC9: non-race DB failure — abort startup with clear message
            throw new IllegalStateException(
                    "FATAL: Admin credentials bootstrap failed: could not insert admin_credentials row. "
                    + "Check database connectivity, disk space, and schema integrity. "
                    + "Underlying cause: " + unexpected.getClass().getSimpleName()
                    + " — " + unexpected.getMessage(),
                    unexpected);
        }
    }

    /**
     * Generates a cryptographically random alphanumeric password of {@link #PASSWORD_LENGTH}
     * characters using {@link SecureRandom}.
     *
     * <p>Entropy: log2(62^16) ≈ 95.3 bits (AC11 minimum: 72 bits).
     * The value is returned as a {@code String} and used only in the INFO log line (AC11 —
     * not logged at DEBUG or TRACE).
     */
    private String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Internal DTO for reading admin_credentials rows from the DB. */
    private record CredentialRow(UUID id, String passwordHash) {}
}
