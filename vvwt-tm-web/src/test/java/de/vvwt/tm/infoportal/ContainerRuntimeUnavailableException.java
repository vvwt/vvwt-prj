// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

/**
 * Typed exception thrown when the container runtime (Podman/Docker socket) is unreachable before
 * Testcontainers ITs attempt to start containers.
 *
 * <p>AC5 (E38S10) mandates that a missing runtime fails loudly with an operator-actionable message
 * — never silently skipping or hanging. This exception carries the socket path and remediation
 * instructions in its message.
 *
 * <p>Thrown by {@code InfoPortalPublisherIT.assertRuntimeAvailable()} in a {@code @BeforeAll},
 * guaranteeing that all test methods in the class fail rather than skipping when the runtime is
 * absent.
 *
 * @see InfoPortalPublisherIT
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S10.story.md">
 *     E38S10 AC5</a>
 */
public class ContainerRuntimeUnavailableException extends RuntimeException {

    /**
     * Constructs the exception with an operator-actionable message naming the missing socket and
     * the remediation steps.
     *
     * @param message operator-actionable message (must name the expected socket path and
     *     remediation — never a generic "no Docker found" message)
     */
    public ContainerRuntimeUnavailableException(String message) {
        super(message);
    }
}
