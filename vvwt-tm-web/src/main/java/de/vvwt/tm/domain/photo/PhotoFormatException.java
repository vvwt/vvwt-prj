package de.vvwt.tm.domain.photo;

/**
 * Thrown when an uploaded file is not a JPEG or PNG image (E12S02 AC7).
 *
 * <p>Maps to HTTP 400 via {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}. Only {@code
 * image/jpeg} and {@code image/png} are accepted (AC7).
 *
 * @see PhotoStorageService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story
 *     E12S02</a>
 */
public class PhotoFormatException extends RuntimeException {

    public PhotoFormatException(String message) {
        super(message);
    }
}
