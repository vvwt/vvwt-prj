package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.draft.DistributionMode;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.draft.GameMode;
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
 * absent/null to {@link DistributionMode#SEQUENTIAL}). The frontend reads this value to preserve
 * the user's selection on round-trip save/load.
 *
 * <h2>E51S20 — gameMode and distributionMode as type-safe enums</h2>
 *
 * <p>Both {@link #gameMode} and {@link #distributionMode} fields are now typed as {@link GameMode}
 * / {@link DistributionMode} enums. Jackson serializes them via {@link GameMode#getWireFormat()} /
 * {@link DistributionMode#getWireFormat()} — the JSON wire-format strings ({@code "siegerehrung"},
 * {@code "roundRobin"}, {@code "sequential"}, {@code "round_robin"}) are preserved unchanged.
 *
 * @see DraftResponse
 * @see DraftBreakResponse
 * @see GameMode
 * @see DistributionMode
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S15">E51S15 — distributionMode feature</a>
 * @see <a href="E51S20">E51S20 — gameMode/distributionMode String→Enum migration</a>
 */
public record DraftSectionResponse(
        int sectionNumber,
        String sortType,
        int groupCount,
        /**
         * Game mode for this phase. Serialized to JSON as the wire-format string (e.g., {@code
         * "siegerehrung"}, {@code "roundRobin"}) via {@link GameMode#getWireFormat()}.
         *
         * @see GameMode
         * @see <a href="E51S20">E51S20 — migrated from String to GameMode enum</a>
         */
        GameMode gameMode,
        int lapBreakTimeMinutes,
        int sectionBreakTimeMinutes,
        int lapTimeMinutes,
        int setQuantity,
        List<DraftBreakResponse> breaks,
        /**
         * Team distribution algorithm for Phase-1 avatar assignment. Always non-null in the
         * response — domain defaults absent/null to {@link DistributionMode#SEQUENTIAL}. Serialized
         * to JSON as the wire-format string (e.g., {@code "sequential"}, {@code "round_robin"}) via
         * {@link DistributionMode#getWireFormat()}.
         *
         * @see DistributionMode
         * @see de.vvwt.tm.tournament.draft.DraftSection#getDistributionMode()
         * @see <a href="E51S15">E51S15 — distributionMode feature</a>
         * @see <a href="E51S20">E51S20 — migrated from String to DistributionMode enum</a>
         */
        DistributionMode distributionMode) {

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
