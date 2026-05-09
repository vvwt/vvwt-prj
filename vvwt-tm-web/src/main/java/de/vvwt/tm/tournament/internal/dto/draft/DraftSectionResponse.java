package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.draft.DraftSection;
import java.util.List;

/**
 * REST response DTO for a single draft section.
 *
 * <p>Inventory: E21S01 line 439. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * <h2>E51S15 — distributionMode field</h2>
 *
 * <p>{@code distributionMode} is always present in the response (never null — domain defaults
 * absent/null to {@code "sequential"}). The frontend reads this value to preserve the user's
 * selection on round-trip save/load.
 *
 * @see DraftResponse
 * @see DraftBreakResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S15">E51S15 — distributionMode feature</a>
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
        List<DraftBreakResponse> breaks,
        /**
         * Team distribution algorithm for Phase-1 avatar assignment. Always non-null in the
         * response — domain defaults absent/null to {@code "sequential"}.
         *
         * @see de.vvwt.tm.tournament.draft.DraftSection#getDistributionMode()
         * @see <a href="E51S15">E51S15 — distributionMode feature</a>
         */
        String distributionMode) {

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
                section.getBreaks().stream().map(DraftBreakResponse::from).toList(),
                section.getDistributionMode());
    }
}
