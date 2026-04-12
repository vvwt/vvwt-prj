package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.MappingAssignment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * REST request DTO for the mapping apply endpoint (E05S08 AC2).
 *
 * <p>The request body is an array of {@link AssignmentRequest} tuples:
 * {@code [{teamId, groupNumber, groupPosition}, ...]}
 *
 * @param assignments the list of team-to-slot assignments
 */
public record MappingApplyRequest(
        @NotNull @Valid List<AssignmentRequest> assignments
) {

    /**
     * Converts this DTO to a list of domain {@link MappingAssignment} objects.
     *
     * @return domain assignments
     */
    public List<MappingAssignment> toDomain() {
        return assignments.stream()
                .map(a -> new MappingAssignment(a.teamId(), a.groupNumber(), a.groupPosition()))
                .toList();
    }

    /**
     * One team-to-slot assignment tuple.
     *
     * @param teamId        the team UUID
     * @param groupNumber   target group number (≥ 1)
     * @param groupPosition target position within the group (≥ 1)
     */
    public record AssignmentRequest(
            @NotNull UUID teamId,
            @Min(1) int groupNumber,
            @Min(1) int groupPosition
    ) {}
}
