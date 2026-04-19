package de.vvwt.tm.domain;

/**
 * Thrown when a resource limit is exceeded (E07S02 AC2 — display device limit).
 *
 * <p>Translated to HTTP 429 (Too Many Requests) by {@link
 * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * <p>The {@code currentCount} and {@code maxCount} fields are included in the error response body
 * per AC8 (structured error with current count and maximum).
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S02.story.md">Story
 *     E07S02</a>
 */
public class TooManyRequestsException extends RuntimeException {

    private final long currentCount;
    private final int maxCount;

    /**
     * Constructs a {@code TooManyRequestsException} with the current and maximum counts.
     *
     * @param message human-readable description of the limit exceeded
     * @param currentCount the current number of registered devices of this type
     * @param maxCount the configured maximum allowed
     */
    public TooManyRequestsException(String message, long currentCount, int maxCount) {
        super(message);
        this.currentCount = currentCount;
        this.maxCount = maxCount;
    }

    /**
     * @return the current number of registered DISPLAY devices for this tenant+location
     */
    public long getCurrentCount() {
        return currentCount;
    }

    /**
     * @return the configured maximum number of DISPLAY devices per tenant+location
     */
    public int getMaxCount() {
        return maxCount;
    }
}
