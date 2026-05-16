// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer.audio;

/**
 * Thrown when an uploaded file is not in the accepted .mp3 format.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.audio.AudioFormatException} per DEC-21 module layout
 * (public sub-package of the {@code timer} Modulith module, option A per user decision 2026-04-27).
 * Constructor signature preserved verbatim per C-3.
 *
 * <p>Caught cross-module by {@code web.GlobalExceptionHandler.handleAudioFormat} (E26S03 updates
 * the import) → HTTP 415 Unsupported Media Type.
 *
 * @see AudioStorageService
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02</a>
 */
public class AudioFormatException extends RuntimeException {

    public AudioFormatException(String message) {
        super(message);
    }
}
