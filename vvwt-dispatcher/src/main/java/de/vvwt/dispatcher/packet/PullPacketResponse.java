package de.vvwt.dispatcher.packet;

import de.vvwt.worker.types.CanonicalPhaseDef;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for a successful {@code POST /pull-packet} call (HTTP 200).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code packetId} — UUID of the assigned packet</li>
 *   <li>{@code jobId} — UUID of the owning job</li>
 *   <li>{@code jobDef} — {@link CanonicalPhaseDef} for the job; workers never see avatar UUIDs (AC11)</li>
 *   <li>{@code rankFrom} — inclusive start rank (0-based Lehmer)</li>
 *   <li>{@code rankTo} — exclusive end rank</li>
 *   <li>{@code deadline} — ISO-8601 instant by which the result must be submitted;
 *                          {@code = assignedAt + packetTimeoutMinutes} (AC5)</li>
 * </ul>
 *
 * <p>See Story E01S07 AC5 and AC11.
 */
public record PullPacketResponse(
        UUID packetId,
        UUID jobId,
        CanonicalPhaseDef jobDef,
        long rankFrom,
        long rankTo,
        Instant deadline) {
}
