package de.vvwt.slotopt.worker.runtime;

import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/register-key} from the worker's perspective.
 *
 * <p>Mirrors the wire shape of the dispatcher's {@code RegisterKeyRequest} record (per Brief D-10,
 * O-6 (iii)). Fields: {@code workerId}, {@code role}, {@code algorithm}, {@code publicKeyBytes}.
 *
 * <p>Story: E41S04 AC-REGISTER-KEY-WITH-ALGORITHM (moved to E63S01 shared library).
 */
public record RegisterKeyRequest(
        UUID workerId, String role, String algorithm, byte[] publicKeyBytes) {}
