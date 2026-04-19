package de.vvwt.tm.domain.audio;

/**
 * Thrown when a disk I/O error occurs during audio file storage operations.
 *
 * <p>Story E11S01 — AC7: Disk I/O error → 500 with descriptive message. Mapped to HTTP 500 by
 * {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story
 *     E11S01</a>
 */
public class AudioStorageException extends RuntimeException {

    public AudioStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
