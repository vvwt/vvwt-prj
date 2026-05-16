package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.draft.DraftSection;
import java.util.List;

/**
 * REST response DTO for a single draft section.
 *
 * <p>Inventory: E21S01 line 439. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * <h2>E51S15 / E58S02 — distributionMode field</h2>
 *
 * <p>{@code distributionMode} is always present in the response (never null — domain defaults
 * absent/null to {@code "sequential"}). The frontend reads this value to preserve the user's
 * selection on round-trip save/load. Migrated from {@code DistributionMode} enum to {@code String}
 * by E58S02 (DEC-73 D-2).
 *
 * <h2>E58S01 — gameMode migrated from GameMode enum to String (DEC-73 D-5)</h2>
 *
 * <p>{@link #gameMode} is now a plain {@code String} (registry key) instead of the removed {@code
 * GameMode} enum. Jackson serializes it as a JSON string directly. Wire-format is preserved: the
 * same String values ({@code "siegerehrung"}, {@code "roundRobin"}) flow through the API as before.
 *
 * @see DraftResponse
 * @see DraftBreakResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S15">E51S15 — distributionMode feature</a>
 * @see <a href="E58S01">E58S01 — gameMode String migration; AC5</a>
 * @see <a href="E58S02">E58S02 — distributionMode String migration; AC5</a>
 */
public record DraftSectionResponse(
        int sectionNumber,
        String sortType,
        int groupCount,
        /**
         * Game mode registry key for this phase (e.g., {@code "siegerehrung"}, {@code
         * "roundRobin"}). Serialized to JSON as a plain String.
         *
         * <p>Migrated from {@code GameMode} enum to {@code String} by E58S01 (DEC-73 D-5).
         * Wire-format unchanged.
         *
         * @see <a href="E58S01">E58S01 — AC5 GameMode enum removed</a>
         */
        String gameMode,
        int lapBreakTimeMinutes,
        int sectionBreakTimeMinutes,
        int lapTimeMinutes,
        int setQuantity,
        List<DraftBreakResponse> breaks,
        /**
         * Team distribution algorithm for Phase-1 avatar assignment. Always non-null in the
         * response — domain defaults absent/null to {@code "sequential"}. Serialized to JSON as a
         * plain String (e.g., {@code "sequential"}, {@code "round_robin"}). Migrated from {@code
         * DistributionMode} enum to {@code String} by E58S02 (DEC-73 D-2).
         *
         * @see de.vvwt.tm.tournament.draft.DraftSection#getDistributionMode()
         * @see <a href="E51S15">E51S15 — distributionMode feature</a>
         * @see <a href="E58S02">E58S02 — DistributionMode enum removed</a>
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
