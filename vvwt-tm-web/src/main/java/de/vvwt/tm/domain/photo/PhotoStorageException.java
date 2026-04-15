package de.vvwt.tm.domain.photo;

/**
 * Thrown when a filesystem I/O error occurs during photo storage operations (E12S02 AC8).
 *
 * <p>Maps to HTTP 500 via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 * The message is included in the response body to aid operator diagnosis (AC8).
 *
 * @see PhotoStorageService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story E12S02</a>
 */
public class PhotoStorageException extends RuntimeException {

    public PhotoStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public PhotoStorageException(String message) {
        super(message);
    }
}
