package de.vvwt.tm.infrastructure.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * REST request body for {@code PUT /api/tournaments/{tournamentId}/draft} (AC2 — E05S06).
 *
 * @see DraftSectionRequest
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story
 *     E05S06 AC2</a>
 */
public record DraftRequest(@NotNull @Valid List<DraftSectionRequest> sections) {}
