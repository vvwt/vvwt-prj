package de.vvwt.tm.domain;

import java.util.UUID;

/**
 * Value object representing a single team-to-slot assignment in a mapping operation.
 *
 * <p>Used by {@link PhaseMappingService#applyMapping} and {@link PhaseMappingService#redoMapping}
 * as the domain-level representation of the organizer's mapping choice. The REST layer
 * deserializes the request body into {@code List<MappingAssignment>} via the DTO adapter.
 *
 * @param teamId        the team to assign (must be a participating team in the tournament)
 * @param groupNumber   target group number in the new phase (1-indexed)
 * @param groupPosition target position within the group (1-indexed, must be sequential)
 * @see PhaseMappingService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S08.story.md">Story E05S08 AC2</a>
 */
public record MappingAssignment(UUID teamId, int groupNumber, int groupPosition) {}
