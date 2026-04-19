package de.vvwt.tm.auth.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

/**
 * Reconstruction-in-place bootstrap for admin credentials (E15S03).
 *
 * <p>Orchestrates generation → hashing → persistence of the admin password at
 * application startup, delegating each concern to a dedicated collaborator:
 * <ul>
 *   <li>{@link PasswordGenerator} — generates a cryptographically random plaintext
 *       password (E15S01).</li>
 *   <li>{@link AdminCredentialsDao} — reads and writes the {@code admin_credentials}
 *       table via a per-tenant DataSource (E15S02, DEC-20).</li>
 *   <li>{@link PasswordEncoder} — hashes the plaintext via BCrypt (Spring Security).</li>
 * </ul>
 *
 * <h2>@Order(2)</h2>
 * <p>Runs after {@code DefaultTenantBootstrapRunner} ({@code @Order(1)}) — the default
 * tenant's DataSource must be fully initialised before admin credentials can be persisted
 * to the per-tenant H2 database.
 *
 * <h2>First-start flow (AC3)</h2>
 * <ol>
 *   <li>Query via {@link AdminCredentialsDao#findExisting()}.</li>
 *   <li>If empty: generate plaintext → encode → insert hash → log plaintext at INFO.</li>
 *   <li>If present: load existing hash → log "set at first start" INFO line (AC4).</li>
 * </ol>
 *
 * <h2>Concurrent-start race (AC5)</h2>
 * <p>When two processes start simultaneously and both see an empty table, one INSERT
 * wins and the other receives {@link DataIntegrityViolationException}. The loser catches
 * the exception, re-queries via {@link AdminCredentialsDao#findExisting()}, and uses the
 * winner's hash. The Generator is NOT invoked a second time.
 *
 * <h2>Encoder failure (AC6)</h2>
 * <p>If the {@link PasswordEncoder} throws during hashing, the exception propagates
 * immediately. {@link AdminCredentialsDao#insertNew} is never called, preventing
 * half-written state.
 *
 * <h2>Reconstruction-in-place note (DEC-21, DEC-22)</h2>
 * <p>This class lives in {@code de.vvwt.tm.auth.internal} — the new Modulith-compliant
 * location. The legacy {@code de.vvwt.tm.auth.AdminCredentialsBootstrap} is left
 * untouched until E15S07 (atomic cutover). During the parallel-development phase, this
 * class coexists without collision via two mechanisms:
 * (a) This class is <em>not</em> annotated with {@code @Component} — it is a plain POJO
 *     that will be registered as a Spring bean by the {@code @Configuration} class in
 *     E15S04 ({@code AuthConfiguration}). Until E15S04 lands, the legacy bean handles
 *     the runtime bootstrap. The {@code @Order(2)} annotation is present here and will
 *     be honoured when E15S04 wires this class into the Spring context.
 * (b) This class does NOT implement {@code AdminCredentialsProvider} — that interface
 *     remains wired to the legacy bean until E15S04 (SecurityConfig reconstruction).
 *
 * @see PasswordGenerator
 * @see AdminCredentialsDao
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S03.story.md">Story E15S03</a>
 * @since E15S03
 */
@Order(2)
public class AdminCredentialsBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialsBootstrap.class);

    private final PasswordGenerator generator;
    private final AdminCredentialsDao dao;
    private final PasswordEncoder encoder;

    /**
     * Constructs an {@code AdminCredentialsBootstrap} with the three collaborators.
     *
     * <p>In production, Spring injects the beans wired in the {@code auth} module's
     * {@code @Configuration} class (E15S04). The {@code AdminCredentialsDao} receives
     * a per-tenant DataSource resolved via {@code TenantDataSourceResolver} (DEC-20).
     *
     * @param generator the password generator; must not be {@code null}
     * @param dao       the credentials DAO; must not be {@code null}
     * @param encoder   the password encoder (BCrypt); must not be {@code null}
     */
    public AdminCredentialsBootstrap(PasswordGenerator generator,
                                     AdminCredentialsDao dao,
                                     PasswordEncoder encoder) {
        this.generator = generator;
        this.dao = dao;
        this.encoder = encoder;
    }

    // -------------------------------------------------------------------------
    // ApplicationRunner
    // -------------------------------------------------------------------------

    /**
     * Executes the admin-credentials bootstrap at application startup.
     *
     * <p>Implements the first-start and subsequent-start paths described in the class
     * Javadoc. Never throws on the concurrent-start race path (AC5) — re-queries instead.
     *
     * @param args Spring application arguments (not used)
     * @throws RuntimeException if the PasswordEncoder throws (AC6 — encoder failure path)
     * @throws IllegalStateException if a DIVE is caught but the re-query returns empty
     *         (should be impossible given the singleton_guard constraint)
     */
    @Override
    public void run(ApplicationArguments args) {
        Optional<AdminCredentialsDao.CredentialRecord> existing = dao.findExisting();

        if (existing.isPresent()) {
            // AC4: subsequent-start — load existing hash, log notice, return without regenerating
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin password: (set at first start — check startup log)");
            log.info("[tm-auth] Username: admin");
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin credentials bootstrap complete — existing credentials loaded");
            return;
        }

        // First-start path: generate → hash → persist → log
        generateAndPersist();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Generates a random password, hashes it, persists the hash, and logs the plaintext
     * at INFO level (AC3 — preserved behaviour from legacy bootstrap).
     *
     * <p>On concurrent-start race ({@link DataIntegrityViolationException}): re-queries
     * via {@link AdminCredentialsDao#findExisting()} and uses the winner's hash (AC5).
     * Does NOT retry the INSERT or regenerate the password.
     *
     * <p>On encoder failure: exception propagates immediately; no INSERT is attempted (AC6).
     */
    private void generateAndPersist() {
        // AC3: generate plaintext, encode to hash (AC6: if encoder throws, propagates here)
        String plaintext = generator.generate();
        String hash = encoder.encode(plaintext);
        UUID id = UUID.randomUUID();

        try {
            dao.insertNew(id, hash);

            // AC3: log plaintext at INFO only — never at DEBUG or TRACE
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin password: {}", plaintext);
            log.info("[tm-auth] Username: admin");
            log.info("[tm-auth] IMPORTANT: Save this password. It is only shown once.");
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin credentials generated and persisted (first start)");

        } catch (DataIntegrityViolationException race) {
            // AC5: concurrent-start race — another process won the INSERT.
            log.info("[tm-auth] Concurrent start detected: another process inserted admin "
                    + "credentials first. Re-querying. "
                    + "Constraint violation: {}", race.getMessage());

            Optional<AdminCredentialsDao.CredentialRecord> winner = dao.findExisting();
            if (winner.isEmpty()) {
                throw new IllegalStateException(
                        "FATAL: Caught concurrent admin_credentials insertion violation but "
                        + "subsequent re-query found no row. Database is in an inconsistent state. "
                        + "Root cause: " + race.getMessage(), race);
            }
            log.info("[tm-auth] Admin credentials loaded from concurrent-start winner "
                    + "(plaintext shown by the primary process — check its startup log)");
        }
    }
}
