package de.vvwt.tm.domain.audio;

/**
 * Thrown when an uploaded file is not in the accepted .mp3 format.
 *
 * <p>Story E11S01 — AC7: Non-.mp3 upload → 415 with message naming allowed formats.
 * Mapped to HTTP 415 by {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story E11S01</a>
 */
public class AudioFormatException extends RuntimeException {

    public AudioFormatException(String message) {
        super(message);
    }
}
