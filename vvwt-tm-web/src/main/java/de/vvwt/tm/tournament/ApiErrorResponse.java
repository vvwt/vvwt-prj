// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Structured JSON error response body for REST API errors (E21S10, AC-TDD-ApiErrorResponse,
 * AC-PKG-ApiErrorResponse, inventory row 412).
 *
 * <p>Boundary-API type at the public root {@code de.vvwt.tm.tournament} per DEC-21 D-8 (its effects
 * cross contexts — consumed by other bounded contexts' error handlers). Replaces legacy {@code
 * de.vvwt.tm.infrastructure.web.ApiErrorResponse} at E21S13 atomic cutover.
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code status} — HTTP status code (e.g., 400, 403, 500)
 *   <li>{@code error} — HTTP status reason phrase
 *   <li>{@code message} — Human-readable error description. MUST NOT contain stack traces, internal
 *       class FQNs, or SQL statement text (AC-SEC-NO-EXCEPTION-LEAK)
 *   <li>{@code messageKey} — i18n key for the SPA translation layer
 *   <li>{@code path} — Request path that triggered the error
 *   <li>{@code timestamp} — ISO-8601 instant when the error occurred
 *   <li>{@code fieldErrors} — Optional per-field validation errors (null/absent for non-400s)
 * </ul>
 *
 * @see de.vvwt.tm.web.GlobalExceptionHandler
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-30">DEC-30 — Spotless formatting (AOSP)</a>
 * @see <a href="E21S10">E21S10 — inventory row 412</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiErrorResponse {

    private final int status;
    private final String error;
    private final String message;
    private final String messageKey;
    private final String path;
    private final Instant timestamp;
    private final List<FieldError> fieldErrors;

    private ApiErrorResponse(Builder builder) {
        this.status = builder.status;
        this.error = builder.error;
        this.message = builder.message;
        this.messageKey = builder.messageKey;
        this.path = builder.path;
        this.timestamp = builder.timestamp;
        this.fieldErrors = builder.fieldErrors;
    }

    /** Jackson deserialization constructor. */
    @JsonCreator
    ApiErrorResponse(
            @JsonProperty("status") int status,
            @JsonProperty("error") String error,
            @JsonProperty("message") String message,
            @JsonProperty("messageKey") String messageKey,
            @JsonProperty("path") String path,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("fieldErrors") List<FieldError> fieldErrors) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.messageKey = messageKey;
        this.path = path;
        this.timestamp = timestamp;
        this.fieldErrors = fieldErrors;
    }

    /**
     * @return HTTP status code
     */
    public int getStatus() {
        return status;
    }

    /**
     * @return HTTP status reason phrase
     */
    public String getError() {
        return error;
    }

    /**
     * @return Human-readable error description (no stack traces per AC-SEC-NO-EXCEPTION-LEAK)
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return i18n message key for the SPA translation layer
     */
    public String getMessageKey() {
        return messageKey;
    }

    /**
     * @return Request path that triggered the error
     */
    public String getPath() {
        return path;
    }

    /**
     * @return ISO-8601 timestamp of the error
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * @return Per-field validation errors; {@code null} for non-validation errors (omitted from
     *     JSON by {@link JsonInclude#NON_NULL})
     */
    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }

    /** Per-field validation error detail (400 responses). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class FieldError {
        private final String field;
        private final String rejectedValue;
        private final String message;
        private final String messageKey;

        @JsonCreator
        public FieldError(
                @JsonProperty("field") String field,
                @JsonProperty("rejectedValue") Object rejectedValue,
                @JsonProperty("message") String message,
                @JsonProperty("messageKey") String messageKey) {
            this.field = field;
            this.rejectedValue = rejectedValue != null ? rejectedValue.toString() : null;
            this.message = message;
            this.messageKey = messageKey;
        }

        /**
         * @return The field name that failed validation
         */
        public String getField() {
            return field;
        }

        /**
         * @return The rejected value as a string; {@code null} if the value was null
         */
        public String getRejectedValue() {
            return rejectedValue;
        }

        /**
         * @return Human-readable validation message
         */
        public String getMessage() {
            return message;
        }

        /**
         * @return i18n message key for this field error
         */
        public String getMessageKey() {
            return messageKey;
        }
    }

    /**
     * Builder for {@link ApiErrorResponse}.
     *
     * <p>Required fields: {@code status}, {@code error}, {@code message}, {@code messageKey}.
     */
    public static final class Builder {
        private final int status;
        private final String error;
        private final String message;
        private final String messageKey;
        private String path;
        private Instant timestamp = Instant.now();
        private List<FieldError> fieldErrors;

        /**
         * @param status HTTP status code
         * @param error HTTP status reason phrase
         * @param message Human-readable description (MUST NOT leak stack traces)
         * @param messageKey i18n key for the SPA
         */
        public Builder(int status, String error, String message, String messageKey) {
            this.status = status;
            this.error = error;
            this.message = message;
            this.messageKey = messageKey;
        }

        /**
         * @param path request path
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * @param timestamp error timestamp; defaults to {@link Instant#now()}
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * @param fieldErrors per-field validation errors; {@code null} to omit
         */
        public Builder fieldErrors(List<FieldError> fieldErrors) {
            this.fieldErrors = fieldErrors;
            return this;
        }

        /**
         * @return the constructed {@link ApiErrorResponse}
         */
        public ApiErrorResponse build() {
            return new ApiErrorResponse(this);
        }
    }
}
