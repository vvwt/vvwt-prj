package de.vvwt.slotopt.dispatcher.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP response body for {@code POST /api/register-key}.
 *
 * <p>JSON field names match the E37S02 spec section (b) verbatim per AC-REGISTER-KEY-REQUEST-RESPONSE
 * (E37S05).
 *
 * <p>Story: E37S05; Spec: E37S02 spec (b)
 */
public record RegisterKeyResponse(UUID workerId, String role, String algorithm,
        Instant registeredAt) {}
