package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.draft.DraftSection;
import java.util.List;

/**
 * REST response DTO for a single draft section.
 *
 * <p>Inventory: E21S01 line 439. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see DraftResponse
 * @see DraftBreakResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public record DraftSectionResponse(
        int sectionNumber,
        String sortType,
        int groupCount,
        String gameMode,
        int lapBreakTimeMinutes,
        int sectionBreakTimeMinutes,
        int lapTimeMinutes,
        int setQuantity,
        List<DraftBreakResponse> breaks) {

    /**
     * Maps a {@link DraftSection} domain object to this response DTO.
     *
     * @param section the domain object; must not be {@code null}
     * @return the response DTO
     */
    public static DraftSectionResponse from(DraftSection section) {
        return new DraftSectionResponse(
                section.getSectionNumber(),
                section.getSortType(),
                section.getGroupCount(),
                section.getGameMode(),
                section.getLapBreakTimeMinutes(),
                section.getSectionBreakTimeMinutes(),
                section.getLapTimeMinutes(),
                section.getSetQuantity(),
                section.getBreaks().stream().map(DraftBreakResponse::from).toList());
    }
}
