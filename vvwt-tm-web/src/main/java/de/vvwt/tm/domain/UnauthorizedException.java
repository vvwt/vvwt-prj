package de.vvwt.tm.domain;

/**
 * Thrown when a device token is invalid, expired, or belongs to a different tenant (AC8, E06S03).
 *
 * <p>Mapped to HTTP 401 by {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S03.story.md">Story
 *     E06S03</a>
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
