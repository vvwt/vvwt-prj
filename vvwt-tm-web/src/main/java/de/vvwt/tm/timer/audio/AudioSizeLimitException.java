package de.vvwt.tm.timer.audio;

/**
 * Thrown when an uploaded audio file exceeds the configured size limit.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.audio.AudioSizeLimitException} per DEC-21 module layout
 * (public sub-package of the {@code timer} Modulith module, option A per user decision 2026-04-27).
 * Constructor signature preserved verbatim per C-3.
 *
 * <p>Caught cross-module by {@code web.GlobalExceptionHandler.handleAudioSizeLimit} (E26S03 updates
 * the import) → HTTP 413 Payload Too Large.
 *
 * @see AudioStorageService
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02</a>
 */
public class AudioSizeLimitException extends RuntimeException {

    public AudioSizeLimitException(String message) {
        super(message);
    }
}
