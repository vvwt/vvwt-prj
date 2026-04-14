package de.vvwt.tm.domain;

/**
 * Thrown when a request is authenticated but not authorized for the requested operation (E06S06, AC12).
 *
 * <p>Distinguishes from {@link UnauthorizedException} (invalid/missing token → 401) by conveying
 * that the device token is valid but the device is not authorized for the requested field
 * (e.g., submitting a score for a field the tablet is not assigned to → HTTP 403).
 *
 * <p>Mapped to HTTP 403 by {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S06.story.md">Story E06S06</a>
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
