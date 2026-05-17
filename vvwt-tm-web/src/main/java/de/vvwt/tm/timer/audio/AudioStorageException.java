// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer.audio;

/**
 * Thrown when a disk I/O error occurs during audio file storage operations.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.audio.AudioStorageException} per DEC-21 module layout
 * (public sub-package of the {@code timer} Modulith module, option A per user decision 2026-04-27).
 * Constructor signature preserved verbatim per C-3.
 *
 * <p>Caught cross-module by {@code web.GlobalExceptionHandler.handleAudioStorage} (E26S03 updates
 * the import) → HTTP 500 Internal Server Error.
 *
 * @see AudioStorageService
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02</a>
 */
public class AudioStorageException extends RuntimeException {

    public AudioStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
