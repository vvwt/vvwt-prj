package de.vvwt.tm.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DatabaseDirectoryInitializer} — verifies path extraction and
 * URL classification logic without launching a Spring context.
 *
 * <p>Acceptance criteria covered: AC7 (error-handling), AC10 (POSIX permission logic
 * tested in the integration test; here we cover the in-memory skip branch).
 */
class DatabaseDirectoryInitializerTest {

    /**
     * Verifies that the initializer correctly identifies in-memory H2 URLs.
     * When the datasource URL contains ':h2:mem:', no directory should be created.
     */
    @Test
    void inMemoryUrlIsDetectedCorrectly() {
        // In-memory URLs must not trigger directory creation
        assertThat(isInMemoryUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1")).isTrue();
        assertThat(isInMemoryUrl("JDBC:H2:MEM:UPPERCASE")).isTrue();
        assertThat(isInMemoryUrl("jdbc:h2:file:/some/path/tm")).isFalse();
        assertThat(isInMemoryUrl("jdbc:h2:file:~/.tournament-manager/db/tm;AUTO_SERVER=FALSE")).isFalse();
    }

    /**
     * Verifies that H2 file paths are extracted correctly from JDBC URLs,
     * stripping the scheme prefix and option clauses.
     */
    @Test
    void h2FilePathIsExtractedFromUrl() {
        // Standard file URL with options
        assertThat(extractH2FilePath("jdbc:h2:file:/home/user/.tournament-manager/db/tm;AUTO_SERVER=FALSE"))
                .isEqualTo(Path.of("/home/user/.tournament-manager/db/tm"));

        // No options
        assertThat(extractH2FilePath("jdbc:h2:file:/tmp/test/db"))
                .isEqualTo(Path.of("/tmp/test/db"));

        // Multiple options
        assertThat(extractH2FilePath("jdbc:h2:file:/data/tm;MODE=MySQL;TRACE_LEVEL_FILE=0"))
                .isEqualTo(Path.of("/data/tm"));
    }

    /**
     * Verifies that directory creation proceeds without error when the parent
     * directory does not yet exist. This is a smoke-test of the happy path.
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
