// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vvwt.tm.tournament.Device;
import java.time.LocalDateTime;

/**
 * Response DTO for the device status endpoint (E21S06 AC-TDD-DTOs).
 *
 * <p>{@code assignedField} and {@code registeredAt} are nullable.
 *
 * <p>{@code pin} is included for SCORING_TABLET devices in REGISTERED status to enable the
 * PIN-on-revisit flow (E49S02 M-A mechanism — DEC-24 §E PIN-lifetime-stable). {@code pin} is {@code
 * null} for DISPLAY devices (no PIN required) and is omitted from JSON serialization via
 * {@code @JsonInclude(NON_NULL)}.
 *
 * @see Device
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 * @see <a href="E49S02">E49S02 — PIN-on-revisit: extend /status response with pin field</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceStatusResponse(
        String status,
        Integer assignedField,
        LocalDateTime registeredAt,
        String deviceName,
        String configuration,
        String pin) {

    /**
     * Backward-compat 3-arg constructor for test code that predates the {@code deviceName}, {@code
     * configuration}, and {@code pin} fields (E21S13 cutover — DEC-22 refactor phase).
     *
     * @param status device status
     * @param assignedField assigned field number (nullable)
     * @param registeredAt registration timestamp (nullable)
     */
    public DeviceStatusResponse(String status, Integer assignedField, LocalDateTime registeredAt) {
        this(status, assignedField, registeredAt, null, null, null);
    }

    /**
     * Backward-compat 5-arg constructor for test code that predates the {@code pin} field (E49S02 —
     * preserves existing 5-arg call sites).
     *
     * @param status device status
     * @param assignedField assigned field number (nullable)
     * @param registeredAt registration timestamp (nullable)
     * @param deviceName human-readable device name (nullable)
     * @param configuration JSON configuration blob (nullable)
     */
    public DeviceStatusResponse(
            String status,
            Integer assignedField,
            LocalDateTime registeredAt,
            String deviceName,
            String configuration) {
        this(status, assignedField, registeredAt, deviceName, configuration, null);
    }

    /**
     * Factory method — builds a response from a persisted {@link Device}.
     *
     * <p>The {@code pin} field is included for SCORING_TABLET devices (non-null) and omitted for
     * DISPLAY devices ({@code null}, suppressed by {@code @JsonInclude(NON_NULL)}).
     *
     * @param device the device
     * @return the status response DTO
     */
    public static DeviceStatusResponse from(Device device) {
        return new DeviceStatusResponse(
                device.getStatus(),
                device.getAssignedField(),
                device.getRegisteredAt(),
                device.getDeviceName(),
                device.getConfiguration(),
                device.getPin());
    }
}
