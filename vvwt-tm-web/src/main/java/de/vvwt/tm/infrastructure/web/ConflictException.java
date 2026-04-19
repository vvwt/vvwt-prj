package de.vvwt.tm.infrastructure.web;

/**
 * Thrown by domain services or REST controllers when a business constraint is violated (AC1 —
 * constraint violations → HTTP 409 Conflict, E05S03).
 *
 * <h2>Examples</h2>
 *
 * <ul>
 *   <li>Activating a second tournament when one is already active (DEC-5)
 *   <li>Any other domain invariant violation that should surface as HTTP 409
 * </ul>
 *
 * <p>This is a runtime exception. Callers should not catch it — it propagates to {@link
 * GlobalExceptionHandler#handleConflict} which maps it to an HTTP 409 response.
 *
 * @see GlobalExceptionHandler
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story
 *     E05S03</a>
 */
public class ConflictException extends RuntimeException {

    /**
     * @param message Human-readable description of the conflict. Included in the {@code message}
     *     field of the API error response (AC1). Must not contain internal system details (stack
     *     traces, SQL, etc.).
     */
    public ConflictException(String message) {
        super(message);
    }
}
