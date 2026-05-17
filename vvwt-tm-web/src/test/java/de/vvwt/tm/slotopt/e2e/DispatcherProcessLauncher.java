// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.e2e;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Launches and stops the {@code vvwt-slotopt-dispatcher} fat JAR as an external OS subprocess.
 *
 * <p>This launcher provides a live dispatcher for TM end-to-end integration tests
 * (AC-TEST-E2E-LEG2-LIVE-WORKER) without introducing a compile-time dependency on the dispatcher
 * module (AC-GOV-NO-DISPATCHER-COMPILE-DEP, DEC-11). The dispatcher JAR is resolved from the
 * Maven build output at test runtime — no hardcoded path.
 *
 * <h2>Readiness probing (AC-ERR-E2E-DETERMINISTIC)</h2>
 *
 * <p>{@link #start()} polls {@code GET /api/algorithms} with condition-wait (no fixed sleep). The
 * dispatcher is declared ready when the probe returns HTTP 2xx. The poll uses a 500 ms interval
 * with a 60-second overall timeout; a diagnostic {@link AssertionError} is thrown on timeout.
 *
 * <h2>No compile dependency on dispatcher (AC-GOV-NO-DISPATCHER-COMPILE-DEP)</h2>
 *
 * <p>The JAR path is resolved at runtime from the filesystem ({@code
 * vvwt-slotopt-dispatcher/target/vvwt-slotopt-dispatcher-*.jar}). This class never imports any
 * class from {@code vvwt-slotopt-dispatcher}.
 *
 * <h2>H2 in-memory database</h2>
 *
 * <p>The dispatcher is started with an H2 in-memory datasource ({@code
 * jdbc:h2:mem:e2eit-dispatcher;...}) so that it is ephemeral and does not require a real database.
 *
 * @see SlotOptE2EIT
 */
class DispatcherProcessLauncher {

    private static final long READINESS_TIMEOUT_MS = 60_000;
    private static final long READINESS_POLL_INTERVAL_MS = 500;

    private final int port;
    private Process process;

    /**
     * Creates a launcher that will use the given port.
     *
     * @param port TCP port for the dispatcher's HTTP server
     */
    DispatcherProcessLauncher(int port) {
        this.port = port;
    }

    /**
     * Allocates a random available port and creates a launcher bound to it.
     *
     * @return a new {@code DispatcherProcessLauncher} with a randomly assigned free port
     * @throws IOException if no free port can be found
     */
    static DispatcherProcessLauncher withRandomPort() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            freePort = socket.getLocalPort();
        }
        return new DispatcherProcessLauncher(freePort);
    }

    /**
     * Starts the dispatcher subprocess and waits for it to become ready.
     *
     * <p>Resolves the dispatcher fat JAR path from the filesystem, builds a {@link ProcessBuilder}
     * with required Spring Boot properties, starts the process, and condition-polls {@code GET
     * /api/algorithms} until HTTP 2xx is returned or the readiness timeout elapses.
     *
     * @throws IOException if the JAR cannot be found or the process fails to start
     * @throws InterruptedException if the polling thread is interrupted
     * @throws AssertionError if the dispatcher does not become ready within the timeout
     */
    void start() throws IOException, InterruptedException {
        Path jarPath = resolveDispatcherJar();

        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-jar");
        command.add(jarPath.toAbsolutePath().toString());
        command.add("--server.port=" + port);
        command.add(
                "--spring.datasource.url="
                        + "jdbc:h2:mem:e2eit-dispatcher-"
                        + port
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        command.add("--spring.datasource.driver-class-name=org.h2.Driver");
        command.add("--spring.datasource.username=sa");
        command.add("--spring.datasource.password=");
        command.add("--spring.jpa.hibernate.ddl-auto=create-drop");
        command.add("--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect");
        // Reduce startup noise
        command.add("--logging.level.root=WARN");
        command.add("--logging.level.de.vvwt=INFO");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        process = pb.start();

        awaitReadiness();
    }

    /**
     * Returns the base URI of the started dispatcher subprocess.
     *
     * @return {@code http://localhost:<port>}
     */
    URI getBaseUri() {
        return URI.create("http://localhost:" + port);
    }

    /**
     * Stops the dispatcher subprocess and waits for it to exit.
     *
     * <p>Destroys the process (SIGTERM on Unix, TerminateProcess on Windows), then waits up to 10
     * seconds for it to exit. Forces a kill if it does not exit in time.
     */
    void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void awaitReadiness() throws InterruptedException {
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(READINESS_POLL_INTERVAL_MS))
                        .build();
        URI probeUri = URI.create(getBaseUri() + "/api/algorithms");

        long deadline = System.currentTimeMillis() + READINESS_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new AssertionError(
                        "DispatcherProcessLauncher: dispatcher process died before becoming"
                                + " ready (port="
                                + port
                                + ")");
            }
            try {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(probeUri)
                                .GET()
                                .timeout(Duration.ofMillis(READINESS_POLL_INTERVAL_MS))
                                .build();
                HttpResponse<Void> response =
                        client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return; // dispatcher is ready
                }
            } catch (java.net.http.HttpTimeoutException | java.net.ConnectException ignored) {
                // not ready yet — continue polling
            } catch (IOException e) {
                // transient error — continue polling
            }
            Thread.sleep(READINESS_POLL_INTERVAL_MS);
        }
        stop();
        throw new AssertionError(
                "DispatcherProcessLauncher: dispatcher did not become ready within "
                        + READINESS_TIMEOUT_MS
                        + " ms on port "
                        + port
                        + ". Check that the dispatcher JAR is built and the H2 datasource"
                        + " properties are correct.");
    }

    private static Path resolveDispatcherJar() throws IOException {
        // Resolve relative to this module's basedir:
        // vvwt-tm-web/../vvwt-slotopt-dispatcher/target/vvwt-slotopt-dispatcher-*.jar
        Path moduleBasedir = resolveModuleBasedir();
        Path dispatcherTarget = moduleBasedir.getParent().resolve("vvwt-slotopt-dispatcher/target");

        if (!Files.isDirectory(dispatcherTarget)) {
            throw new IOException(
                    "DispatcherProcessLauncher: dispatcher target directory not found at "
                            + dispatcherTarget
                            + ". Run 'mvn package -pl vvwt-slotopt-dispatcher' first.");
        }

        try (Stream<Path> stream = Files.list(dispatcherTarget)) {
            return stream.filter(
                            p -> {
                                String name = p.getFileName().toString();
                                return name.startsWith("vvwt-slotopt-dispatcher")
                                        && name.endsWith(".jar")
                                        && !name.contains("sources")
                                        && !name.contains("javadoc")
                                        && !name.contains("tests");
                            })
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new IOException(
                                            "DispatcherProcessLauncher: no dispatcher JAR found in"
                                                    + " "
                                                    + dispatcherTarget
                                                    + ". Run 'mvn package -pl"
                                                    + " vvwt-slotopt-dispatcher' first."));
        }
    }

    private static Path resolveModuleBasedir() {
        // The Maven surefire/failsafe plugin sets 'user.dir' to the module basedir during tests.
        // Fallback: walk up from cwd to find the vvwt-tm-web module.
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            Path candidate = Paths.get(userDir);
            if (candidate.getFileName() != null
                    && candidate.getFileName().toString().equals("vvwt-tm-web")) {
                return candidate;
            }
            // Walk up to find vvwt-tm-web
            Path p = candidate;
            while (p != null) {
                Path tmWeb = p.resolve("vvwt-tm-web");
                if (Files.isDirectory(tmWeb)) {
                    return tmWeb;
                }
                p = p.getParent();
            }
        }
        throw new IllegalStateException(
                "DispatcherProcessLauncher: cannot resolve vvwt-tm-web module basedir from"
                        + " user.dir="
                        + userDir);
    }
}
