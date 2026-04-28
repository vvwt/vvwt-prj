package de.vvwt.tm.timer.audio;

import java.time.Instant;

/**
 * Value object representing metadata for a stored tournament audio file.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.audio.AudioFileMetadata} per DEC-21 module layout
 * (public sub-package of the {@code timer} Modulith module, option A per user decision 2026-04-27).
 *
 * <p><strong>Pattern A (DEC-40 §2026-04-27 Clarification):</strong> This record serves BOTH as the
 * service-method projection (returned by {@link AudioStorageService#upload}, {@link
 * AudioStorageService#list}) AND as the HTTP wire shape serialized directly by {@code
 * AudioController} (E26S03). No separate web-tier DTO wrapping is needed because the projection
 * shape equals the wire shape — no field omission, no cross-context aggregation, no aliasing (all
 * Clause B (a)/(c)/(d) conditions are NOT satisfied). The legacy {@code
 * de.vvwt.tm.infrastructure.web.audio.AudioMetadataResponse} 1:1 wrapper is DELETED by E26S02 (not
 * re-authored at S03 or elsewhere).
 *
 * <p>Fields preserved verbatim per C-3 + C-8 (JSON wire-format parity). Empirically verified at
 * Discovery 2026-04-27: both {@code domain.audio.AudioFileMetadata} and {@code
 * infrastructure.web.audio.AudioMetadataResponse} carried identical 4-field shape.
 *
 * @param category the audio category (START, END, PAUSE)
 * @param filename the original uploaded filename as provided by the client
 * @param sizeBytes the size of the stored file in bytes
 * @param uploadedAt the instant the file was stored (derived from filesystem mtime)
 * @see AudioStorageService
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02 (Pattern A rationale)</a>
 */
public record AudioFileMetadata(
        AudioCategory category, String filename, long sizeBytes, Instant uploadedAt) {}
