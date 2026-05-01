package de.vvwt.tm.infrastructure;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * EnvironmentPostProcessor that creates the H2 database parent directory with owner-only
 * permissions (mode {@code 0700} on POSIX hosts) before H2 opens the database file.
 *
 * <p>Running as an {@link EnvironmentPostProcessor} (registered via {@code
 * META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor}) guarantees that the
 * directory exists and has the correct permissions before the datasource bean is created — H2 must
 * not be the first to create it with world-readable defaults.
 *
 * <p>Behaviour:
 *
 * <ul>
 *   <li>Reads the effective H2 file path from {@code TM_DB_PATH} env var or falls back to {@code
 *       ${user.home}/.tournament-manager/db/tm}.
 *   <li>In-memory URLs ({@code jdbc:h2:mem:…}) are skipped — no directory needed.
 *   <li>On POSIX hosts: creates parent directory with mode {@code rwx------} (0700). Owner-only:
 *       protects the single-file database from other local users (AC10).
 *   <li>On Windows: creates the directory with default user-profile ACLs; logs an advisory note
 *       that explicit ACL hardening is out of scope for V1 (AC10).
 *   <li>If directory creation fails (permissions, disk-full, path invalid): logs a clear error
 *       naming the exact path and IOException cause, then throws {@link IllegalStateException} —
 *       Spring Boot exits with non-zero status code (AC7).
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S02.story.md">Story
 *     E02S02</a>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseDirectoryInitializer implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDirectoryInitializer.class);

    /** Owner-only directory permissions: rwx------ (0700). */
    private static final String POSIX_DIR_MODE = "rwx------";

    /**
     * Called by Spring Boot after environment is prepared but before any beans are created.
     *
     * @param environment the application environment
     * @param application the Spring application
     */
    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        String datasourceUrl = resolveDatasourceUrl(environment);

        if (isInMemoryUrl(datasourceUrl)) {
            log.debug(
                    "[tm-bootstrap] Datasource URL is in-memory — skipping directory"
                            + " initialisation");
            return;
        }

        Path dbFilePath = extractH2FilePath(datasourceUrl);
        Path parentDirectory = dbFilePath.getParent();

        if (parentDirectory == null) {
            // Relative path with no parent directory component — H2 uses the working dir.
            // Nothing to create; log and proceed.
            log.info(
                    "[tm-bootstrap] H2 path '{}' has no parent directory — using process working"
                            + " directory",
                    dbFilePath);
            return;
        }

        if (Files.exists(parentDirectory)) {
            log.debug("[tm-bootstrap] DB directory already exists: {}", parentDirectory);
            return;
        }

        createDirectoryWithOwnerOnlyPermissions(parentDirectory);
    }

    /**
     * Resolves the effective datasource URL in priority order:
     *
     * <ol>
     *   <li>{@code spring.datasource.url} Spring property (handles Spring property placeholders
     *       already resolved by the time this processor runs)
     *   <li>{@code TM_DB_PATH} environment variable used to construct the URL
     *   <li>Default path under {@code user.home}
     * </ol>
     */
    private String resolveDatasourceUrl(ConfigurableEnvironment environment) {
        // Check if a full datasource URL is already in the environment
        String configuredUrl = environment.getProperty("spring.datasource.url");
        if (configuredUrl != null) {
            return configuredUrl;
        }

        // Construct URL from TM_DB_PATH or user.home fallback
        String tmDbPath = environment.getProperty("TM_DB_PATH");
        if (tmDbPath != null && !tmDbPath.isBlank()) {
            return "jdbc:h2:file:" + tmDbPath + ";AUTO_SERVER=FALSE";
        }

        String userHome = System.getProperty("user.home");
        return "jdbc:h2:file:" + userHome + "/.tournament-manager/db/tm;AUTO_SERVER=FALSE";
    }

    /**
     * Returns {@code true} if the URL points to an in-memory H2 database. In-memory databases
     * ({@code jdbc:h2:mem:…}) do not use the filesystem.
     */
    private boolean isInMemoryUrl(String url) {
        return url != null && url.contains(":h2:mem:");
    }

    /**
     * Extracts the filesystem path of the H2 database file from a {@code
     * jdbc:h2:file:<path>[;<options>]} URL.
     *
     * <p>H2 appends {@code .mv.db} to the returned path automatically — we do not append it here;
     * we only need the parent directory.
     */
    private Path extractH2FilePath(String url) {
        // URL format: jdbc:h2:file:<path>[;<key>=<value>...]
        // Strip the prefix and any trailing ;options
        String withoutScheme = url.replaceFirst("(?i)^jdbc:h2:file:", "");
        // Remove H2 options (everything from first ';' onward)
        int semiColon = withoutScheme.indexOf(';');
        String rawPath = (semiColon >= 0) ? withoutScheme.substring(0, semiColon) : withoutScheme;
        return Path.of(rawPath.trim());
    }

    /**
     * Creates {@code directory} and all required parent directories.
     *
     * <p>On POSIX hosts: creates with mode {@code rwx------} (0700) using atomic {@link
     * Files#createDirectories(Path, FileAttribute[])} to prevent a race window between creation and
     * permission setting.
     *
     * <p>On non-POSIX hosts (Windows): creates with default user-profile ACLs and logs an advisory
     * that explicit ACL hardening is not applied.
     *
     * @throws IllegalStateException if directory creation fails — Spring Boot will catch this and
     *     exit with a non-zero status code (AC7)
     */
    private void createDirectoryWithOwnerOnlyPermissions(Path directory) {
        boolean isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

        try {
            if (isPosix) {
                Set<PosixFilePermission> ownerOnly =
                        PosixFilePermissions.fromString(POSIX_DIR_MODE);
                FileAttribute<Set<PosixFilePermission>> posixAttributes =
                        PosixFilePermissions.asFileAttribute(ownerOnly);
                Files.createDirectories(directory, posixAttributes);
                log.info(
                        "[tm-bootstrap] Created DB directory with owner-only permissions (0700):"
                                + " {}",
                        directory);
            } else {
                Files.createDirectories(directory);
                log.info(
                        "[tm-bootstrap] Created DB directory (Windows — default user-profile ACLs"
                                + " apply): {}",
                        directory);
                log.info(
                        "[tm-bootstrap] Advisory: on Windows, explicit ACL hardening is not applied"
                                + " in V1 (AC10). The directory inherits user-profile defaults.");
            }
        } catch (IOException ioException) {
            String message =
                    String.format(
                            "[tm-bootstrap] FATAL: Cannot create H2 database directory '%s'. Check"
                                + " that the path is writable and that no disk quota or permission"
                                + " blocks access. Underlying cause: %s — %s",
                            directory.toAbsolutePath(),
                            ioException.getClass().getSimpleName(),
                            ioException.getMessage());
            log.error(message);
            // Throwing here causes Spring Boot to exit with non-zero status (AC7).
            // No silent fallback to in-memory database.
            throw new IllegalStateException(message, ioException);
        }
    }
}
