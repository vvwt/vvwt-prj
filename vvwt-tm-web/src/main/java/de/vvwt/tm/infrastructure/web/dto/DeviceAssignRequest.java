package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Request body for PUT /api/devices/{id}/assign (AC5, E06S03). */
public record DeviceAssignRequest(
        @NotNull(message = "fieldNumber is required")
                @Min(value = 1, message = "fieldNumber must be >= 1")
                Integer fieldNumber) {}
