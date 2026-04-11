package de.vvwt.dispatcher.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP response body for a successful {@code POST /register-key} call (AC1 of E01S06).
 *
 * @param keyId        opaque UUID assigned to this registration
 * @param role         {@code "worker"} or {@code "submitter"}
 * @param registeredAt the timestamp when this key was registered
 */
public record RegisterKeyResponse(UUID keyId, String role, Instant registeredAt) {
}
