// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.vvwt.tm.tournament.Device;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for device list and admin operations (E21S06 AC-TDD-DTOs, E49S01 AC3).
 *
 * <p>Carries the device summary: id, deviceToken, deviceType, assignedField (nullable), status,
 * registeredAt (nullable), deviceName (nullable), configuration (nullable).
 *
 * <p>{@code pin} has been intentionally removed per E49S01 AC3 — the PIN is a one-time out-of-band
 * credential and must not be exposed in list/summary responses.
 *
 * @see Device
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 * @see <a href="E49S01">E49S01 — AC3: pin removed from summary response</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceSummaryResponse(
        UUID id,
        String deviceToken,
        String deviceType,
        Integer assignedField,
        String status,
        LocalDateTime registeredAt,
        String deviceName,
        String configuration) {

    /**
     * Factory method — builds a summary response from a persisted {@link Device}.
     *
     * @param device the device
     * @return the summary response DTO
     */
    public static DeviceSummaryResponse from(Device device) {
        return new DeviceSummaryResponse(
                device.getId(),
                device.getDeviceToken(),
                device.getDeviceType(),
                device.getAssignedField(),
                device.getStatus(),
                device.getRegisteredAt(),
                device.getDeviceName(),
                device.getConfiguration());
    }
}
