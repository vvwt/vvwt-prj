package de.vvwt.tm.infrastructure.display;

/**
 * Thrown when no active phase exists for the current tenant's tournament (E07S04, AC7).
 *
 * <p>Caught by {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler#handleNoActivePhase} and
 * translated to HTTP 404 with body {@code { "status": "NO_ACTIVE_PHASE" }}.
 *
 * @see de.vvwt.tm.infrastructure.web.GlobalExceptionHandler
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S04.story.md">Story
 *     E07S04</a>
 */
public class NoActivePhaseException extends RuntimeException {

    /** Creates the exception with no additional message (the body is always the same). */
    public NoActivePhaseException() {
        super("No active phase exists for the current tenant");
    }
}
