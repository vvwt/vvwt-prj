package de.vvwt.tm.domain.certificate;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable value object representing certificate template metadata stored in H2
 * (E12S04 AC1, AC3 — filename, format, upload timestamp, file size).
 *
 * @param tournamentId  the tournament this template belongs to (AC8 — one per tournament)
 * @param filename      original client-provided filename (for display purposes)
 * @param format        detected file format: {@code "html"} or {@code "svg"}
 * @param uploadedAt    timestamp when the template was last uploaded / replaced
 * @param fileSizeBytes file size in bytes (as stored on disk)
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story E12S04</a>
 */
public record CertificateTemplateMetadata(
        UUID tournamentId,
        String filename,
        String format,
        Instant uploadedAt,
        long fileSizeBytes
) {
}
