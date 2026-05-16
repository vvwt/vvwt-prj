package de.vvwt.tm.auth.internal;

import de.vvwt.tm.auth.AdminCredentialsBootstrap;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Reconstruction-in-place bootstrap for admin credentials (E15S03).
 *
 * <p>Orchestrates generation → hashing → persistence of the admin password at application startup,
 * delegating each concern to a dedicated collaborator:
 *
 * <ul>
 *   <li>{@link PasswordGenerator} — generates a cryptographically random plaintext password
 *       (E15S01).
 *   <li>{@link AdminCredentialsDao} — reads and writes the {@code admin_credentials} table via a
 *       per-tenant DataSource (E15S02, DEC-20).
 *   <li>{@link PasswordEncoder} — hashes the plaintext via BCrypt (Spring Security).
 * </ul>
 *
 * <h2>@Order(2)</h2>
 *
 * <p>Runs after {@code DefaultTenantBootstrapRunner} ({@code @Order(1)}) — the default tenant's
 * DataSource must be fully initialised before admin credentials can be persisted to the per-tenant
 * H2 database.
 *
 * <h2>First-start flow (AC3)</h2>
 *
 * <ol>
 *   <li>Query via {@link AdminCredentialsDao#findExisting()}.
 *   <li>If empty: generate plaintext → encode → insert hash → log plaintext at INFO.
 *   <li>If present: load existing hash → log "set at first start" INFO line (AC4).
 * </ol>
 *
 * <h2>Concurrent-start race (AC5)</h2>
 *
 * <p>When two processes start simultaneously and both see an empty table, one INSERT wins and the
 * other receives {@link DataIntegrityViolationException}. The loser catches the exception,
 * re-queries via {@link AdminCredentialsDao#findExisting()}, and uses the winner's hash. The
 * Generator is NOT invoked a second time.
 *
 * <h2>Encoder failure (AC6)</h2>
 *
 * <p>If the {@link PasswordEncoder} throws during hashing, the exception propagates immediately.
 * {@link AdminCredentialsDao#insertNew} is never called, preventing half-written state.
 *
 * <h2>Reconstruction-in-place note (DEC-21, DEC-22)</h2>
 *
 * <p>This class lives in {@code de.vvwt.tm.auth.internal} — the new Modulith-compliant location.
 * The legacy {@code de.vvwt.tm.auth.internal.AdminCredentialsBootstrap} is left untouched until
 * E15S07 (atomic cutover). During the parallel-development phase, this class coexists without
 * collision via two mechanisms: (a) This class is <em>not</em> annotated with {@code @Component} —
 * it is a plain POJO that will be registered as a Spring bean by the {@code @Configuration} class
 * in E15S04 ({@code AuthConfiguration}). Until E15S04 lands, the legacy bean handles the runtime
 * bootstrap. The {@code @Order(2)} annotation is present here and will be honoured when E15S04
 * wires this class into the Spring context. (b) This class does NOT implement {@code
 * AdminCredentialsProvider} — that interface is exposed via the root {@code auth} package and wired
 * in {@code AuthConfiguration} (E15S04) as a lambda delegate to {@link #getPasswordHash()}.
 *
 * <h2>Hash exposure (E15S04)</h2>
 *
 * <p>{@link #getPasswordHash()} is called by the {@code AdminCredentialsProvider} lambda registered
 * in {@code AuthConfiguration}. It returns the bcrypt hash stored at the end of {@link
 * #run(ApplicationArguments)}. Callers must not invoke this method before {@code run()} has
 * completed (Spring guarantees this — the {@code UserDetailsService} is called only on the first
 * HTTP request, which arrives after all {@code ApplicationRunner} instances have finished).
 *
 * @see PasswordGenerator
 * @see AdminCredentialsDao
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S03.story.md">Story
 *     E15S03</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S04.story.md">Story
 *     E15S04</a>
 * @since E15S03 (renamed DefaultAdminCredentialsBootstrap in E57S01; DEC-58/DEC-72 interface
 *     extraction: implements {@link AdminCredentialsBootstrap} from {@code de.vvwt.tm.auth})
 */
@Order(2)
public class DefaultAdminCredentialsBootstrap implements AdminCredentialsBootstrap {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultAdminCredentialsBootstrap.class);

    /**
     * Holds the bcrypt hash after bootstrap completes. {@code null} before {@link
     * #run(ApplicationArguments)} — callers must not invoke {@link #getPasswordHash()} before
     * startup has finished.
     */
    private volatile String passwordHash;

    private final PasswordGenerator generator;
    private final AdminCredentialsDao dao;
    private final PasswordEncoder encoder;

    /**
     * Constructs an {@code AdminCredentialsBootstrap} with the three collaborators.
     *
     * <p>In production, Spring injects the beans wired in the {@code auth} module's
     * {@code @Configuration} class (E15S04). The {@code AdminCredentialsDao} receives a per-tenant
     * DataSource resolved via {@code TenantDataSourceResolver} (DEC-20).
     *
     * @param generator the password generator; must not be {@code null}
     * @param dao the credentials DAO; must not be {@code null}
     * @param encoder the password encoder (BCrypt); must not be {@code null}
     */
    public DefaultAdminCredentialsBootstrap(
            PasswordGenerator generator, AdminCredentialsDao dao, PasswordEncoder encoder) {
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
     * <p>Implements the first-start and subsequent-start paths described in the class Javadoc.
     * Never throws on the concurrent-start race path (AC5) — re-queries instead.
     *
     * @param args Spring application arguments (not used)
     * @throws RuntimeException if the PasswordEncoder throws (AC6 — encoder failure path)
     * @throws IllegalStateException if a DIVE is caught but the re-query returns empty (should be
     *     impossible given the singleton_guard constraint)
     */
    @Override
    public void run(ApplicationArguments args) {
        Optional<AdminCredentialsDao.CredentialRecord> existing = dao.findExisting();

        if (existing.isPresent()) {
            // AC4: subsequent-start — load existing hash, log notice, return without regenerating
            this.passwordHash = existing.get().passwordHash();
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin password: (set at first start — check startup log)");
            log.info("[tm-auth] Username: admin");
            log.info("[tm-auth] ====================================================");
            log.info(
                    "[tm-auth] Admin credentials bootstrap complete — existing credentials loaded");
            return;
        }

        // First-start path: generate → hash → persist → log
        generateAndPersist();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Generates a random password, hashes it, persists the hash, and logs the plaintext at INFO
     * level (AC3 — preserved behaviour from legacy bootstrap).
     *
     * <p>On concurrent-start race ({@link DataIntegrityViolationException}): re-queries via {@link
     * AdminCredentialsDao#findExisting()} and uses the winner's hash (AC5). Does NOT retry the
     * INSERT or regenerate the password.
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
            this.passwordHash = hash;

            // AC3: log plaintext at INFO only — never at DEBUG or TRACE
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin password: {}", plaintext);
            log.info("[tm-auth] Username: admin");
            log.info("[tm-auth] IMPORTANT: Save this password. It is only shown once.");
            log.info("[tm-auth] ====================================================");
            log.info("[tm-auth] Admin credentials generated and persisted (first start)");

        } catch (DataIntegrityViolationException race) {
            // AC5: concurrent-start race — another process won the INSERT.
            log.info(
                    "[tm-auth] Concurrent start detected: another process inserted admin "
                            + "credentials first. Re-querying. "
                            + "Constraint violation: {}",
                    race.getMessage());

            Optional<AdminCredentialsDao.CredentialRecord> winner = dao.findExisting();
            if (winner.isEmpty()) {
                throw new IllegalStateException(
                        "FATAL: Caught concurrent admin_credentials insertion violation but"
                            + " subsequent re-query found no row. Database is in an inconsistent"
                            + " state. Root cause: "
                                + race.getMessage(),
                        race);
            }
            this.passwordHash = winner.get().passwordHash();
            log.info(
                    "[tm-auth] Admin credentials loaded from concurrent-start winner "
                            + "(plaintext shown by the primary process — check its startup log)");
        }
    }

    // -------------------------------------------------------------------------
    // AdminCredentialsProvider bridge (E15S04)
    // -------------------------------------------------------------------------

    /**
     * Returns the bcrypt hash of the admin password after bootstrap completes.
     *
     * <p>Called by the {@code AdminCredentialsProvider} lambda registered in {@code
     * AuthConfiguration} (E15S04). This method is invoked at authentication time (first HTTP
     * request), which is guaranteed to be after all {@code ApplicationRunner} instances — including
     * this one — have finished.
     *
     * @return the bcrypt hash of the admin password; never {@code null} after bootstrap
     * @throws IllegalStateException if called before {@link #run(ApplicationArguments)} has
     *     completed (should not happen in production)
     */
    @Override
    public String getPasswordHash() {
        String hash = this.passwordHash;
        if (hash == null) {
            throw new IllegalStateException(
                    "DefaultAdminCredentialsBootstrap.getPasswordHash() called before bootstrap "
                            + "completed. Ensure callers are invoked after ApplicationRunner "
                            + "(@Order(2)) has run.");
        }
        return hash;
    }
}
