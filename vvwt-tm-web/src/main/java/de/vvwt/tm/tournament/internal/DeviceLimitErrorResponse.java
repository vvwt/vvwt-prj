package de.vvwt.tm.tournament.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * HTTP 409 response body for device limit exceeded errors (E21S06 AC-TDD-DeviceLimitErrorResponse).
 *
 * <p>Returned by {@link de.vvwt.tm.tournament.DeviceController} when a {@link
 * DeviceLimitExceededException} is thrown during device registration. JSON shape:
 *
 * <pre>{@code
 * {
 *   "errorCode": "DEVICE_LIMIT_EXCEEDED",
 *   "configuredLimit": 10,
 *   "currentCount": 10
 * }
 * }</pre>
 *
 * <p>Distinct from the legacy {@code de.vvwt.tm.infrastructure.web.DeviceLimitErrorResponse} which
 * is an HTTP 429 response for display device limits.
 *
 * @see DeviceLimitExceededException
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 421)</a>
 */
public class DeviceLimitErrorResponse {

    private final String errorCode;
    private final int configuredLimit;
    private final long currentCount;
    private final String messageKey;

    /**
     * Constructs the response with all fields.
     *
     * @param errorCode machine-readable error code (e.g. {@code "DEVICE_LIMIT_EXCEEDED"})
     * @param configuredLimit the configured maximum device count
     * @param currentCount the current number of registered devices for the tenant
     * @param messageKey i18n message key for the error (e.g. {@code "error.device.limitExceeded"})
     */
    @JsonCreator
    public DeviceLimitErrorResponse(
            @JsonProperty("errorCode") String errorCode,
            @JsonProperty("configuredLimit") int configuredLimit,
            @JsonProperty("currentCount") long currentCount,
            @JsonProperty("messageKey") String messageKey) {
        this.errorCode = errorCode;
        this.configuredLimit = configuredLimit;
        this.currentCount = currentCount;
        this.messageKey = messageKey;
    }

    /**
     * Backward-compat 3-arg constructor (E21S13 cutover — DEC-22 refactor phase).
     *
     * @param errorCode machine-readable error code
     * @param configuredLimit the configured maximum device count
     * @param currentCount the current number of registered devices for the tenant
     */
    public DeviceLimitErrorResponse(String errorCode, int configuredLimit, long currentCount) {
        this(errorCode, configuredLimit, currentCount, null);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getConfiguredLimit() {
        return configuredLimit;
    }

    public long getCurrentCount() {
        return currentCount;
    }

    /**
     * Alias for {@link #getConfiguredLimit()} — retained for backward compatibility with consumer
     * test code that used the legacy {@code de.vvwt.tm.infrastructure.web.DeviceLimitErrorResponse}
     * API (E21S13 cutover — DEC-22 refactor phase).
     */
    public int getMaxCount() {
        return configuredLimit;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
