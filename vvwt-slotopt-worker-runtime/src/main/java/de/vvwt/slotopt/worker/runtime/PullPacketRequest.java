package de.vvwt.slotopt.worker.runtime;

import java.util.List;
import java.util.UUID;

/**
 * HTTP request body for {@code POST /api/pull-packet} from the worker's perspective.
 *
 * <p>Worker-side DTO mirroring the dispatcher's {@code PullPacketRequest} wire shape (per Brief
 * D-10, O-6 (i)). Fields: {@code workerId}, {@code supportedAlgorithms} (list of algorithm IDs the
 * worker supports per DEC-43 D2 — V1: always {@code ["Ed25519"]}).
 *
 * <p>Story: E41S05 AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT (moved to E63S01 shared library).
 */
public record PullPacketRequest(UUID workerId, List<String> supportedAlgorithms) {}
