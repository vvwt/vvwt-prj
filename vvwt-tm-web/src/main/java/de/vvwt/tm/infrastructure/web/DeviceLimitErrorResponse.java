package de.vvwt.tm.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Structured error response for device registration limit exceeded (E07S02 AC2, AC8).
 *
 * <p>Extends the standard error shape with {@code currentCount} and {@code maxCount} fields as
 * required by AC8: "the device limit error (AC2) includes the current count and the maximum in the
 * error body."
 *
 * <p>Returned with HTTP 429 (Too Many Requests) when a DISPLAY device registration would exceed
 * {@code vvwt.devices.max-display-count}.
 *
 * @see GlobalExceptionHandler#handleTooManyRequests
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S02.story.md">Story
 *     E07S02</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DeviceLimitErrorResponse {

    private final int status;
    private final String error;
    private final String message;
    private final String messageKey;
    private final String path;
    private final Instant timestamp;
    private final long currentCount;
    private final int maxCount;

    @JsonCreator
    public DeviceLimitErrorResponse(
            @JsonProperty("status") int status,
            @JsonProperty("error") String error,
            @JsonProperty("message") String message,
            @JsonProperty("messageKey") String messageKey,
            @JsonProperty("path") String path,
            @JsonProperty("currentCount") long currentCount,
            @JsonProperty("maxCount") int maxCount) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.messageKey = messageKey;
        this.path = path;
        this.timestamp = Instant.now();
        this.currentCount = currentCount;
        this.maxCount = maxCount;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPath() {
        return path;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    /** Current number of registered DISPLAY devices for this tenant+location (AC8). */
    public long getCurrentCount() {
        return currentCount;
    }

    /** Configured maximum number of DISPLAY devices per tenant+location (AC8). */
    public int getMaxCount() {
        return maxCount;
    }
}
