package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;

/**
 * Request DTO for assigning a device to a field number (E21S06 AC-TDD-DTOs).
 *
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 */
public record DeviceAssignRequest(
        @JsonProperty("fieldNumber") @Min(value = 1, message = "fieldNumber must be at least 1")
                int fieldNumber) {

    @JsonCreator
    public DeviceAssignRequest {}
}
