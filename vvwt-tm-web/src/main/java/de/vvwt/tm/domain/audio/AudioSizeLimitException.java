package de.vvwt.tm.domain.audio;

/**
 * Thrown when an uploaded audio file exceeds the configured size limit.
 *
 * <p>Story E11S01 — AC7: File exceeds configurable size limit (default 10 MB) → 413.
 * Mapped to HTTP 413 by {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story E11S01</a>
 */
public class AudioSizeLimitException extends RuntimeException {

    public AudioSizeLimitException(String message) {
        super(message);
    }
}
