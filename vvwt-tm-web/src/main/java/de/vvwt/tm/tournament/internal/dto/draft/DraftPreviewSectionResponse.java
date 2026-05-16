// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.draft.DraftPreviewSection;

/**
 * REST response DTO for one section in a draft preview.
 *
 * <p>Inventory: E21S01 line 435. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see DraftPreviewResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public record DraftPreviewSectionResponse(
        int phaseNumber,
        int groupCount,
        int teamsPerGroup,
        int matchesPerGroup,
        int totalLaps,
        int totalMatches,
        int estimatedTimeMinutes) {

    /**
     * Maps a {@link DraftPreviewSection} domain object to this response DTO.
     *
     * @param preview the domain preview section; must not be {@code null}
     * @return the response DTO
     */
    public static DraftPreviewSectionResponse from(DraftPreviewSection preview) {
        return new DraftPreviewSectionResponse(
                preview.getPhaseNumber(),
                preview.getGroupCount(),
                preview.getTeamsPerGroup(),
                preview.getMatchesPerGroup(),
                preview.getTotalLaps(),
                preview.getTotalMatches(),
                preview.getEstimatedTimeMinutes());
    }
}
