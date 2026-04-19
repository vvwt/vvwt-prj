package de.vvwt.tm.domain.photo;

/**
 * Thrown when an uploaded photo exceeds the configured size limit (E12S02 AC7).
 *
 * <p>Maps to HTTP 400 via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}.
 *
 * @see PhotoStorageService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story
 *     E12S02</a>
 */
public class PhotoSizeException extends RuntimeException {

    public PhotoSizeException(String message) {
        super(message);
    }
}
