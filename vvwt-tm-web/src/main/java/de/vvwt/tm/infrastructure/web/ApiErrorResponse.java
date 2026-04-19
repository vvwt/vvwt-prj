package de.vvwt.tm.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Structured JSON error response body for all REST API errors (AC1, AC9, E05S03).
 *
 * <h2>Fields</h2>
 *
 * <ul>
 *   <li>{@code status} — HTTP status code (e.g., 400, 404, 409, 500)
 *   <li>{@code error} — HTTP status reason phrase (e.g., "Bad Request")
 *   <li>{@code message} — Human-readable error description
 *   <li>{@code messageKey} — i18n message key for the SPA translation layer (AC9). The SPA uses
 *       this key to look up a locale-specific message. If no specific key applies, the value is
 *       {@code "error.internal"} for 500s or a specific key like {@code "error.validation"}, {@code
 *       "error.notFound"}, {@code "error.conflict"}.
 *   <li>{@code path} — The request path that triggered the error (aids debugging)
 *   <li>{@code timestamp} — ISO-8601 instant when the error occurred
 *   <li>{@code fieldErrors} — Optional list of per-field validation errors (AC1, 400 responses
 *       only). Null/absent for non-validation errors. {@link JsonInclude#NON_NULL} ensures null
 *       fields are omitted from the serialized JSON (AC2).
 * </ul>
 *
 * <h2>Security (AC1)</h2>
 *
 * <p>The {@code message} field contains a generic message for 500 errors — no stack traces, no
 * internal class names, no database details are included in the response body.
 *
 * @see GlobalExceptionHandler
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story
 *     E05S03</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ApiErrorResponse {

    private final int status;
    private final String error;
    private final String message;
    private final String messageKey;
    private final String path;
    private final Instant timestamp;
    private final java.util.List<FieldError> fieldErrors;

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
            @JsonProperty("fieldErrors") java.util.List<FieldError> fieldErrors) {
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
     * @return Human-readable error description
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return i18n message key for the SPA translation layer (AC9). E.g., {@code
     *     "error.validation"}, {@code "error.notFound"}, {@code "error.conflict"}, {@code
     *     "error.internal"}.
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
     *     JSON by {@link JsonInclude#NON_NULL} — AC2).
     */
    public java.util.List<FieldError> getFieldErrors() {
        return fieldErrors;
    }

    /** Per-field validation error detail (AC1 — field-level error details for 400 responses). */
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
     * <p>Enforces required fields at construction time. {@code fieldErrors} is optional.
     */
    public static final class Builder {
        private final int status;
        private final String error;
        private final String message;
        private final String messageKey;
        private String path;
        private Instant timestamp = Instant.now();
        private java.util.List<FieldError> fieldErrors;

        /**
         * @param status HTTP status code
         * @param error HTTP status reason phrase
         * @param message Human-readable error description (must not contain stack traces)
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
        public Builder fieldErrors(java.util.List<FieldError> fieldErrors) {
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
