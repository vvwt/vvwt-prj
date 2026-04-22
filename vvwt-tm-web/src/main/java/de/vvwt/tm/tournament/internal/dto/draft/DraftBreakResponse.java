package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.draft.DraftBreak;

/**
 * REST response DTO for a single intra-phase break in a draft section.
 *
 * <p>Inventory: E21S01 line 433. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see DraftSectionResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public record DraftBreakResponse(int afterLapNumber, int durationMinutes, String label) {

    /**
     * Maps a {@link DraftBreak} domain object to this response DTO.
     *
     * @param b the domain break; must not be {@code null}
     * @return the response DTO
     */
    public static DraftBreakResponse from(DraftBreak b) {
        return new DraftBreakResponse(b.getAfterLapNumber(), b.getDurationMinutes(), b.getLabel());
    }
}
