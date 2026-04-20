package de.vvwt.tm.tournament.internal.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for assigning a device to a field number (E21S06 AC-TDD-DTOs).
 *
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction</a>
 */
public record DeviceAssignRequest(@JsonProperty("fieldNumber") int fieldNumber) {

    @JsonCreator
    public DeviceAssignRequest {}
}
