package de.vvwt.tm.tournament.internal;

/**
 * Thrown when a new DISPLAY device registration would exceed the per-tenant DISPLAY device cap
 * (E21S13 cutover — DEC-22 refactor phase, migrated from legacy display device limit).
 *
 * <p>Mapped to HTTP 429 Too Many Requests by {@link de.vvwt.tm.tournament.DeviceController}.
 * Response body is a {@link DeviceLimitErrorResponse} carrying {@code currentCount}, {@code
 * configuredLimit}, and {@code messageKey}.
 *
 * <p>Distinct from {@link DeviceLimitExceededException} which enforces the total (all-type) device
 * cap and maps to HTTP 409 Conflict.
 *
 * @see DeviceLimitErrorResponse
 * @see DeviceService
 */
public class DisplayDeviceLimitExceededException extends RuntimeException {

    private final int maxCount;
    private final long currentCount;

    /**
     * Constructs the exception with limit details.
     *
     * @param maxCount the configured maximum DISPLAY device count
     * @param currentCount the current number of DISPLAY devices for the tenant
     */
    public DisplayDeviceLimitExceededException(int maxCount, long currentCount) {
        super(
                "Display device limit exceeded: max is "
                        + maxCount
                        + ", current count is "
                        + currentCount);
        this.maxCount = maxCount;
        this.currentCount = currentCount;
    }

    /** Returns the configured maximum DISPLAY device count. */
    public int getMaxCount() {
        return maxCount;
    }

    /** Returns the current number of DISPLAY devices registered for the tenant. */
    public long getCurrentCount() {
        return currentCount;
    }
}
