// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test for the jlink distribution archive.
 *
 * <p>Runs as a Failsafe integration test (class name ends in {@code IT}) during {@code mvn verify}.
 * Locates the distribution archive produced by {@code maven-assembly-plugin} in {@code target/},
 * extracts it to a temporary directory, launches the application via its bundled launcher script,
 * and validates the health endpoint responds with {@code "status":"UP"}.
 *
 * <p>AC5: end-to-end smoke test — extract → run → health check → DB file exists → stop → clean up.
 * AC6: logs archive size and extracted size.
 *
 * @see <a
 *     href="../../../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S05.story.md">Story
 *     E02S05</a>
 */
@Tag("smoke")
@DisplayName("E02S05 — jlink distribution smoke test")
class JlinkSmokeIT {

    /**
     * AC5: build archive → extract → launch via bundled JRE → health 200 OK → H2 DB exists → stop.
     * AC6: log archive and extracted sizes.
     */
    @Test
    @DisplayName("AC5+AC6: distribution archive is runnable and healthy")
    void distributionArchiveIsRunnableAndHealthy() throws Exception {
        // --------------------------------------------------------------------------
        // Locate the distribution archive in target/
        // --------------------------------------------------------------------------
        Path targetDir = Paths.get(System.getProperty("user.dir"), "target");
        Optional<Path> archivePath = findArchive(targetDir);

        assertThat(archivePath)
                .as("Distribution archive (tournament-manager-*.tar.gz) must exist in target/")
                .isPresent();

        Path archive = archivePath.get();
        System.out.println("[JlinkSmokeIT] Archive: " + archive);

        // AC6: log archive size
        long archiveBytes = Files.size(archive);
        System.out.printf("[JlinkSmokeIT] Archive size: %.1f MB%n", archiveBytes / 1_048_576.0);

        // --------------------------------------------------------------------------
        // Allocate an ephemeral port to avoid conflicts with running services
        // --------------------------------------------------------------------------
        int port = findFreePort();
        System.out.println("[JlinkSmokeIT] Using port: " + port);

        // --------------------------------------------------------------------------
        // Locate the smoke-test.sh script (lives in src/test/scripts/ but Maven
        // does NOT copy test scripts to target/. Use the source tree path via the
        // project base dir system property set by maven-failsafe-plugin.)
        // --------------------------------------------------------------------------
        Path smokeScript =
                Paths.get(
                        System.getProperty("user.dir"), "src", "test", "scripts", "smoke-test.sh");

        assertThat(smokeScript)
                .as("smoke-test.sh must exist at " + smokeScript)
                .exists()
                .isExecutable();

        // --------------------------------------------------------------------------
        // Create a temporary extract directory
        // --------------------------------------------------------------------------
        Path extractDir = Files.createTempDirectory("tm-smoke-");

        // --------------------------------------------------------------------------
        // Run smoke-test.sh
        // --------------------------------------------------------------------------
        ProcessBuilder pb =
                new ProcessBuilder(
                        smokeScript.toAbsolutePath().toString(),
                        archive.toAbsolutePath().toString(),
                        extractDir.toAbsolutePath().toString(),
                        String.valueOf(port));
        pb.redirectErrorStream(true);
        pb.directory(targetDir.toFile());

        System.out.println("[JlinkSmokeIT] Running smoke-test.sh ...");
        Process process = pb.start();

        // Stream output while waiting
        Thread outputThread =
                new Thread(
                        () -> {
                            try (var reader =
                                    new java.io.BufferedReader(
                                            new java.io.InputStreamReader(
                                                    process.getInputStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    System.out.println("[smoke-test.sh] " + line);
                                }
                            } catch (IOException e) {
                                // ignore on shutdown
                            }
                        });
        outputThread.start();

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        outputThread.join(5_000);

        if (!finished) {
            process.destroyForcibly();
            fail("smoke-test.sh timed out after 60 seconds");
        }

        int exitCode = process.exitValue();
        System.out.println("[JlinkSmokeIT] smoke-test.sh exit code: " + exitCode);

        assertThat(exitCode).as("smoke-test.sh must exit 0 — all AC5 checks passed").isEqualTo(0);

        // --------------------------------------------------------------------------
        // AC6: log extracted size
        // --------------------------------------------------------------------------
        // Extract dir was cleaned up by smoke-test.sh trap — report only if it still exists.
        if (Files.exists(extractDir)) {
            long extractedBytes = directorySize(extractDir);
            System.out.printf(
                    "[JlinkSmokeIT] Extracted size: %.1f MB%n", extractedBytes / 1_048_576.0);
        }

        System.out.printf(
                "[JlinkSmokeIT] Archive size summary: %.1f MB (archive)%n",
                archiveBytes / 1_048_576.0);
        System.out.println("[JlinkSmokeIT] Smoke test: PASS");
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    private Optional<Path> findArchive(Path targetDir) throws IOException {
        if (!Files.isDirectory(targetDir)) {
            return Optional.empty();
        }
        try (var stream = Files.list(targetDir)) {
            return stream.filter(
                            p -> {
                                String name = p.getFileName().toString();
                                return name.startsWith("tournament-manager-")
                                        && name.endsWith(".tar.gz");
                            })
                    .findFirst();
        }
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private long directorySize(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0L;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(
                            p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0L;
                                }
                            })
                    .sum();
        }
    }
}
