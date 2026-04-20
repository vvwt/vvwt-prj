package de.vvwt.tm.tournament.internal;

/**
 * Thrown when a new device registration would exceed the configured device limit per tenant (E21S06
 * AC-DEVICELIMIT-ENFORCEMENT).
 *
 * <p>Mapped to HTTP 409 Conflict by {@link de.vvwt.tm.tournament.DeviceController}. Response body
 * is a {@link DeviceLimitErrorResponse} carrying {@code errorCode}, {@code configuredLimit}, and
 * {@code currentCount}.
 *
 * <p>Distinct from the legacy {@code de.vvwt.tm.infrastructure.web.DeviceLimitErrorResponse} which
 * maps to HTTP 429 and tracks only DISPLAY devices.
 *
 * @see DeviceLimitErrorResponse
 * @see DeviceService
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 420)</a>
 */
public class DeviceLimitExceededException extends RuntimeException {

    private final int configuredLimit;
    private final long currentCount;

    /**
     * Constructs the exception with limit details.
     *
     * @param configuredLimit the configured maximum device count
     * @param currentCount the current number of registered devices for the tenant
     */
    public DeviceLimitExceededException(int configuredLimit, long currentCount) {
        super(
                "Device limit exceeded: configured limit is "
                        + configuredLimit
                        + ", current count is "
                        + currentCount);
        this.configuredLimit = configuredLimit;
        this.currentCount = currentCount;
    }

    /**
     * Returns the configured maximum device count.
     *
     * @return the device cap
     */
    public int getConfiguredLimit() {
        return configuredLimit;
    }

    /**
     * Returns the current number of registered devices for the tenant at the time of the exception.
     *
     * @return current device count
     */
    public long getCurrentCount() {
        return currentCount;
    }
}
