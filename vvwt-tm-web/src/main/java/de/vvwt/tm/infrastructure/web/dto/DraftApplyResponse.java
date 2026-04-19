package de.vvwt.tm.infrastructure.web.dto;

import java.util.List;
import java.util.UUID;

/**
 * REST response body for the draft apply endpoint (AC5 — E05S06).
 *
 * <p>Returns the list of Phase IDs created by the apply operation, ordered by phase sequence
 * number.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story
 *     E05S06 AC5</a>
 */
public record DraftApplyResponse(List<UUID> phaseIds) {}
