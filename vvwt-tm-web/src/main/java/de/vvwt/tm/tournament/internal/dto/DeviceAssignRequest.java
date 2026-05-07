package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

/**
 * Request DTO for assigning a device to a field number (E21S06 AC-TDD-DTOs, E49S01 AC5).
 *
 * <p>{@code pin} is required for SCORING_TABLET devices; must be absent (null) for DISPLAY devices
 * (E49S01 AC5 — out-of-band PIN verification).
 *
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 * @see <a href="E49S01">E49S01 — AC5: pin field added to assign request</a>
 */
public record DeviceAssignRequest(
        @JsonProperty("fieldNumber") @Min(value = 1, message = "fieldNumber must be at least 1")
                int fieldNumber,
        @JsonProperty("pin") String pin) {

    @JsonCreator
    public DeviceAssignRequest {}
}
