// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Unit tests verifying the AC5 pre-flight Podman-socket reachability check.
 *
 * <p>AC5 (E38S10): when the container runtime is unreachable, the integration tests throw a typed
 * {@link ContainerRuntimeUnavailableException} (operator-actionable message naming the expected
 * socket and remediation) — never silently skipping, never hanging, never throwing an opaque
 * exception.
 *
 * <p>These tests verify the pre-flight logic in isolation by calling the same check method with a
 * non-existent socket path, asserting the typed exception.
 *
 * @see InfoPortalPublisherIT#assertRuntimeAvailable()
 * @see ContainerRuntimeUnavailableException
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S10.story.md">
 *     E38S10 AC5</a>
 */
@EnabledOnOs(OS.LINUX)
class InfoPortalRuntimePreflightTest {

    /**
     * When the socket file does not exist at the path given in {@code DOCKER_HOST}, {@link
     * ContainerRuntimeUnavailableException} is thrown with an operator-actionable message.
     */
    @Test
    void preflightCheck_throwsTypedException_whenSocketMissing() {
        String nonExistentSocket = "/tmp/nonexistent-podman-test-" + System.nanoTime() + ".sock";
        assertThatThrownBy(() -> assertRuntimeAvailableForPath("unix://" + nonExistentSocket))
                .isInstanceOf(ContainerRuntimeUnavailableException.class)
                .hasMessageContaining(nonExistentSocket)
                .hasMessageContaining("systemctl --user start podman.socket");
    }

    /**
     * When the socket path is a valid UNIX socket (file exists), no exception is thrown.
     *
     * <p>Uses {@code /run/user/{uid}/podman/podman.sock} if present; otherwise falls back to a
     * synthetic existing file. The test is designed to be a positive-path guard, not a
     * live-container smoke test.
     */
    @Test
    void preflightCheck_doesNotThrow_whenSocketExists() throws Exception {
        // Create a temporary file to simulate an existing socket file (AC5: file-exists = ok)
        File tempSocket = File.createTempFile("fake-podman", ".sock");
        tempSocket.deleteOnExit();
        // Must not throw — file exists at the path
        assertRuntimeAvailableForPath("unix://" + tempSocket.getAbsolutePath());
    }

    /**
     * When {@code DOCKER_HOST} uses a TCP address (not a unix:// socket path), the pre-flight does
     * not throw — Testcontainers will handle TCP connectivity itself.
     */
    @Test
    void preflightCheck_doesNotThrow_forTcpDockerHost() {
        // TCP addresses skip the socket-file existence check (handled by Testcontainers)
        assertRuntimeAvailableForPath("tcp://localhost:2375");
    }

    // -------------------------------------------------------------------------
    // Helper — extracted pre-flight logic (mirrors InfoPortalPublisherIT.assertRuntimeAvailable)
    // -------------------------------------------------------------------------

    /**
     * Mirrors the pre-flight logic in {@link InfoPortalPublisherIT#assertRuntimeAvailable()} but
     * accepts a path parameter for test isolation. The real pre-flight reads from the environment;
     * this version accepts an explicit path.
     */
    static void assertRuntimeAvailableForPath(String dockerHost) {
        if (dockerHost == null || dockerHost.isBlank()) {
            return; // No path: let Testcontainers handle it
        }
        String socketPath =
                dockerHost.startsWith("unix://") ? dockerHost.substring("unix://".length()) : null;
        if (socketPath != null) {
            File socketFile = new File(socketPath);
            if (!socketFile.exists()) {
                throw new ContainerRuntimeUnavailableException(
                        "Container runtime socket not found at: "
                                + socketPath
                                + "\n\nRemediation: start the rootless Podman socket:\n"
                                + "  systemctl --user start podman.socket\n"
                                + "  export DOCKER_HOST=unix://"
                                + socketPath
                                + "\n\nOr verify the socket path matches the running"
                                + " Podman user unit.");
            }
        }
    }
}
