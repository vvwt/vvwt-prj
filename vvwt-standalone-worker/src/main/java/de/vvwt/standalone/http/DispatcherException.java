package de.vvwt.standalone.http;

/**
 * Thrown by {@link DispatcherClient} when a dispatcher endpoint returns an error or when a
 * network-level failure occurs.
 *
 * <p>A {@code statusCode} of {@code -1} indicates a network-level failure (I/O error, connection
 * refused, timeout) rather than an HTTP error response.
 */
public final class DispatcherException extends Exception {

    private final int statusCode;
    private final String responseBody;
    private final String endpoint;

    /**
     * Constructs a {@code DispatcherException} for an HTTP error response.
     *
     * @param statusCode HTTP status code, or {@code -1} for network errors
     * @param responseBody response body text (may be null)
     * @param endpoint endpoint that was called (for context)
     */
    public DispatcherException(int statusCode, String responseBody, String endpoint) {
        super(
                String.format(
                        "Dispatcher %s returned HTTP %d: %s", endpoint, statusCode, responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.endpoint = endpoint;
    }

    /**
     * Constructs a {@code DispatcherException} with an underlying cause.
     *
     * @param statusCode HTTP status code, or {@code -1} for network errors
     * @param message error description
     * @param endpoint endpoint that was called
     * @param cause underlying exception
     */
    public DispatcherException(int statusCode, String message, String endpoint, Throwable cause) {
        super(String.format("Dispatcher %s error: %s", endpoint, message), cause);
        this.statusCode = statusCode;
        this.responseBody = message;
        this.endpoint = endpoint;
    }

    /** Returns the HTTP status code, or {@code -1} if this was a network-level failure. */
    public int getStatusCode() {
        return statusCode;
    }

    /** Returns the response body from the dispatcher, or null if not available. */
    public String getResponseBody() {
        return responseBody;
    }

    /** Returns the endpoint path that was called. */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Returns {@code true} if this exception represents an HTTP 4xx error response. 4xx responses
     * indicate a permanent client-side error — do not retry (AC6 of E01S05).
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * Returns {@code true} if this exception represents a network-level failure (connection
     * refused, timeout, I/O error) — eligible for retry (AC5 of E01S05).
     */
    public boolean isNetworkError() {
        return statusCode == -1;
    }

    /**
     * Returns {@code true} if this exception represents an HTTP 5xx error response — eligible for
     * retry (AC5 of E01S05).
     */
    public boolean isServerError() {
        return statusCode >= 500;
    }
}
