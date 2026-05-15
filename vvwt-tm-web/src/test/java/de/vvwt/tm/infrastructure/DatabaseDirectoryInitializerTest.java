package de.vvwt.tm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link DatabaseDirectoryInitializer} — verifies path extraction and URL
 * classification logic without launching a Spring context.
 *
 * <p>Acceptance criteria covered: AC7 (error-handling), AC10 (POSIX permission logic tested in the
 * integration test; here we cover the in-memory skip branch).
 *
 * <p>AC-TEST-RED-FIRST-DBINIT-REROOT (E55S15 / DEC-68 / DEC-22): {@code resolveDatasourceUrl()}
 * with no {@code spring.datasource.url}, no {@code TM_DB_PATH}, but {@code TM_DATA_DIR} set must
 * return a URL containing {@code <TM_DATA_DIR>/db/tm}. Test written RED against the pre-change
 * hardcoded {@code ~/.tournament-manager/db/tm} literal.
 */
class DatabaseDirectoryInitializerTest {

    /**
     * Verifies that the initializer correctly identifies in-memory H2 URLs. When the datasource URL
     * contains ':h2:mem:', no directory should be created.
     */
    @Test
    void inMemoryUrlIsDetectedCorrectly() {
        // In-memory URLs must not trigger directory creation
        assertThat(isInMemoryUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1")).isTrue();
        assertThat(isInMemoryUrl("JDBC:H2:MEM:UPPERCASE")).isTrue();
        assertThat(isInMemoryUrl("jdbc:h2:file:/some/path/tm")).isFalse();
        assertThat(isInMemoryUrl("jdbc:h2:file:~/.tournament-manager/db/tm;AUTO_SERVER=FALSE"))
                .isFalse();
    }

    /**
     * Verifies that H2 file paths are extracted correctly from JDBC URLs, stripping the scheme
     * prefix and option clauses.
     */
    @Test
    void h2FilePathIsExtractedFromUrl() {
        // Standard file URL with options
        assertThat(
                        extractH2FilePath(
                                "jdbc:h2:file:/home/user/.tournament-manager/db/tm;AUTO_SERVER=FALSE"))
                .isEqualTo(Path.of("/home/user/.tournament-manager/db/tm"));

        // No options
        assertThat(extractH2FilePath("jdbc:h2:file:/tmp/test/db"))
                .isEqualTo(Path.of("/tmp/test/db"));

        // Multiple options
        assertThat(extractH2FilePath("jdbc:h2:file:/data/tm;MODE=MySQL;TRACE_LEVEL_FILE=0"))
                .isEqualTo(Path.of("/data/tm"));
    }

    /**
     * Verifies that directory creation proceeds without error when the parent directory does not
     * yet exist. This is a smoke-test of the happy path.
     */
    @Test
    void directoryCreationSucceedsForNonExistentPath(@TempDir Path tempBase) throws IOException {
        Path targetDir = tempBase.resolve("new-subdir").resolve("nested");
        assertThat(Files.exists(targetDir)).isFalse();

        Files.createDirectories(targetDir);

        assertThat(Files.exists(targetDir)).isTrue();
        assertThat(Files.isDirectory(targetDir)).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC-TEST-RED-FIRST-DBINIT-REROOT (E55S15) — DEC-22 RED-first
    // Calls resolveDatasourceUrl() (package-private after
    // AC-IMPL-DATABASEDIRECTORYINITIALIZER-REROOT)
    // directly. Written RED against the pre-change hardcoded ~/.tournament-manager/db/tm literal.
    // Goes GREEN after the fallback is re-rooted to honour TM_DATA_DIR.
    // -------------------------------------------------------------------------

    @Test
    void fallbackUrlDerivedFromTmDataDirWhenNeitherDatasourceUrlNorDbPathSet(
            @TempDir Path tempDataDir) {
        DatabaseDirectoryInitializer initializer = new DatabaseDirectoryInitializer();
        MockEnvironment env = new MockEnvironment();
        // No spring.datasource.url, no TM_DB_PATH
        env.setProperty("TM_DATA_DIR", tempDataDir.toAbsolutePath().toString());

        String url = initializer.resolveDatasourceUrl(env);

        assertThat(url)
                .as(
                        "Fallback URL must derive from TM_DATA_DIR per DEC-68"
                                + " (AC-IMPL-DATABASEDIRECTORYINITIALIZER-REROOT, E55S15)")
                .contains(tempDataDir.toAbsolutePath() + "/db/tm");
    }

    @Test
    void fallbackUrlUsesDefaultRootWhenTmDataDirNotSet() {
        DatabaseDirectoryInitializer initializer = new DatabaseDirectoryInitializer();
        MockEnvironment env = new MockEnvironment();
        // No spring.datasource.url, no TM_DB_PATH, no TM_DATA_DIR

        String url = initializer.resolveDatasourceUrl(env);

        // Should resolve to user.home/.tournament-manager/db/tm (the DEC-68 root default)
        assertThat(url)
                .as(
                        "Fallback URL with no TM_DATA_DIR must use ~/.tournament-manager/db/tm"
                                + " per DEC-68 (E55S15)")
                .contains("/.tournament-manager/db/tm");
    }

    // -------------------------------------------------------------------------
    // Helpers — replicate the core logic from DatabaseDirectoryInitializer
    // to test it in isolation without constructing the full Spring context.
    // -------------------------------------------------------------------------

    private boolean isInMemoryUrl(String url) {
        return url != null && url.toLowerCase().contains(":h2:mem:");
    }

    private Path extractH2FilePath(String url) {
        String withoutScheme = url.replaceFirst("(?i)^jdbc:h2:file:", "");
        int semiColon = withoutScheme.indexOf(';');
        String rawPath = (semiColon >= 0) ? withoutScheme.substring(0, semiColon) : withoutScheme;
        return Path.of(rawPath.trim());
    }
}
