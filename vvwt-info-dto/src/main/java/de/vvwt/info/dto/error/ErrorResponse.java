// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.error;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed error-response hierarchy for the Public Participant Info Service (AC9).
 *
 * <p>Phase 1 closed subtypes:
 *
 * <ul>
 *   <li>{@link FullResyncRequired} — returned on 409 per Brief D-9
 *   <li>{@link RegistrationRejected} — {@code reason} field is an open {@code String} (not closed
 *       enum) per cycle-1 review F-2, allowing E38S04/E38S07 to define and extend reason codes
 *       without DTO contract revision
 *   <li>{@link SignatureInvalid}
 *   <li>{@link SequenceConflict}
 *   <li>{@link RateLimited} — carries {@code retry_after_seconds} and {@code scope}
 * </ul>
 *
 * <p>Jackson polymorphic discrimination via {@code "type"} discriminator field (AC2). Unknown
 * discriminator values produce {@link com.fasterxml.jackson.databind.exc.InvalidTypeIdException}
 * (NOT silent fallback — discriminator handling is orthogonal to {@code
 * FAIL_ON_UNKNOWN_PROPERTIES=false}).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(
            value = ErrorResponse.FullResyncRequired.class,
            name = "FULL_RESYNC_REQUIRED"),
    @JsonSubTypes.Type(
            value = ErrorResponse.RegistrationRejected.class,
            name = "REGISTRATION_REJECTED"),
    @JsonSubTypes.Type(value = ErrorResponse.SignatureInvalid.class, name = "SIGNATURE_INVALID"),
    @JsonSubTypes.Type(value = ErrorResponse.SequenceConflict.class, name = "SEQUENCE_CONFLICT"),
    @JsonSubTypes.Type(value = ErrorResponse.RateLimited.class, name = "RATE_LIMITED")
})
public sealed interface ErrorResponse
        permits ErrorResponse.FullResyncRequired,
                ErrorResponse.RegistrationRejected,
                ErrorResponse.SignatureInvalid,
                ErrorResponse.SequenceConflict,
                ErrorResponse.RateLimited {

    /**
     * Returned on 409 Conflict — the client must perform a full re-synchronisation before
     * continuing (Brief D-9 / AC4).
     *
     * <p>Fields (E38S05):
     *
     * <ul>
     *   <li>{@code required}: always {@code "FULL_RESYNC"} — sentinel so clients can assert on the
     *       semantics, not just the HTTP status.
     *   <li>{@code last_applied_seq}: the server's current {@code last_applied_seq} for the
     *       tournament (tells the client where to resume after snapshot resync); {@code null} when
     *       the server has no record of this tournament (seq=0 case — client must call register
     *       first).
     *   <li>{@code tournament_token}: the server's current bearer token for this tournament; {@code
     *       null} when the server has no record of this tournament.
     * </ul>
     */
    record FullResyncRequired(
            @JsonProperty("required") String required,
            @JsonProperty("last_applied_seq") Long lastAppliedSeq,
            @JsonProperty("tournament_token") String tournamentToken)
            implements ErrorResponse {

        /** Convenience factory for a known tournament's 409 response. */
        public static FullResyncRequired of(long lastAppliedSeq, String tournamentToken) {
            return new FullResyncRequired("FULL_RESYNC", lastAppliedSeq, tournamentToken);
        }

        /** Convenience factory for an unknown tournament's 409 response (seq=0, no token). */
        public static FullResyncRequired unknown() {
            return new FullResyncRequired("FULL_RESYNC", null, null);
        }
    }

    /**
     * Returned when a registration request is rejected. {@code reason} is an open {@code String}
     * (not a closed enum) so that consuming services (E38S04, E38S07, …) can define and extend
     * reason codes without modifying the DTO contract (AC9 cycle-1 F-2 fix).
     *
     * <p>Known Phase-1 reason codes (defined by E38S04): {@code KEY_MISMATCH}, {@code
     * ALGORITHM_DEPRECATED}, {@code ALGORITHM_UNKNOWN}, {@code TENANT_LIMIT_EXCEEDED}, {@code
     * INVITATION_INVALID}, {@code MALFORMED}, {@code INTERNAL_ERROR}.
     */
    record RegistrationRejected(String reason) implements ErrorResponse {}

    /** Returned when the request signature fails verification. */
    record SignatureInvalid() implements ErrorResponse {}

    /** Returned when a sequence/ordering conflict is detected on an update. */
    record SequenceConflict() implements ErrorResponse {}

    /**
     * Returned when the client exceeds a rate limit. {@code retry_after_seconds} is the minimum
     * wait before retrying; {@code scope} identifies the rate-limit dimension (e.g., {@code
     * "TENANT"}, {@code "GLOBAL"}).
     */
    record RateLimited(Integer retry_after_seconds, String scope) implements ErrorResponse {}
}
