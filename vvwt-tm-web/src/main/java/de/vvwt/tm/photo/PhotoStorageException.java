package de.vvwt.tm.photo;

/**
 * Thrown when a filesystem I/O error occurs during photo storage operations (E36S01 Q-1a TDD
 * rebuild).
 *
 * <p>Rebuilt from deleted Q-1b artefact at the same canonical FQN ({@code de.vvwt.tm.photo}) per
 * Brief D-7 Option γ. Constructor signatures preserved verbatim per
 * AC-EXCEPTION-CONSTRUCTORS-PRESERVED: {@code (String message, Throwable cause)} and {@code (String
 * message)}.
 *
 * <p>Maps to HTTP 500 via {@link de.vvwt.tm.web.photo.PhotoExceptionAdvice}. The message is
 * included in the response body to aid operator diagnosis.
 *
 * <p>Historical provenance: originally E12S02; relocated to this module by E23S01 (Q-1b); rebuilt
 * Q-1a RED-first by E36S01 per DEC-22 Iron Law + DEC-41 §3 hierarchy clause (1).
 *
 * @see PhotoStorageService
 * @since E36S01
 */
public class PhotoStorageException extends RuntimeException {

    public PhotoStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public PhotoStorageException(String message) {
        super(message);
    }
}
