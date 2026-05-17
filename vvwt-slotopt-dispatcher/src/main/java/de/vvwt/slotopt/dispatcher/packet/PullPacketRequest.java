// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.packet;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.List;
import java.util.UUID;

/**
 * Request DTO for the {@code POST /api/pull-packet} endpoint.
 *
 * <p>Per AC-PULL-PACKET-DTOs + AC-CAPABILITY-FIELD-OPTIONAL (Brief D-2 backward-compat clause):
 *
 * <ul>
 *   <li>{@code workerId} — UUID of the requesting worker (required)
 *   <li>{@code supportedAlgorithms} — optional list of algorithm IDs the worker supports; absent or
 *       null → treated as {@code ["Ed25519"]} by the service layer; empty array {@code []} → HTTP
 *       400 (per AC-CAPABILITY-FIELD-OPTIONAL)
 * </ul>
 *
 * <p>Mutable POJO (not record) to allow {@code @JsonSetter} null-handling configuration. Jackson
 * deserializes absent fields as null; the service converts null → default. Empty array [] reaches
 * the service as an empty list, which the controller validates before delegating.
 *
 * <p>Story: E37S08; AC-PULL-PACKET-DTOs; AC-CAPABILITY-FIELD-OPTIONAL
 */
public class PullPacketRequest {

    private UUID workerId;

    /**
     * Supported algorithm identifiers.
     *
     * <p>Absent field → null (not set by Jackson). Service converts null → {@code ["Ed25519"]}.
     * Empty list {@code []} → controller returns HTTP 400.
     */
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private List<String> supportedAlgorithms;

    private boolean supportedAlgorithmsPresent = false;

    public UUID getWorkerId() {
        return workerId;
    }

    public void setWorkerId(UUID workerId) {
        this.workerId = workerId;
    }

    public List<String> getSupportedAlgorithms() {
        return supportedAlgorithms;
    }

    public void setSupportedAlgorithms(List<String> supportedAlgorithms) {
        this.supportedAlgorithms = supportedAlgorithms;
        this.supportedAlgorithmsPresent = true;
    }

    /**
     * Returns {@code true} if the {@code supportedAlgorithms} field was present in the JSON request
     * (even if set to null). Used to distinguish absent field from explicit null.
     *
     * <p>Jackson calls {@link #setSupportedAlgorithms(List)} only when the field is present in the
     * JSON. Absent field → setter not called → {@code supportedAlgorithmsPresent} stays {@code
     * false}.
     *
     * @return whether the field appeared in the request JSON
     */
    public boolean isSupportedAlgorithmsPresent() {
        return supportedAlgorithmsPresent;
    }
}
