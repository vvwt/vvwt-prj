package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.internal.draft.DraftConfig;
import java.util.List;

/**
 * REST response body for draft GET endpoint (future use by E21S13 cutover or a save endpoint).
 *
 * <p>Inventory: E21S01 line 437. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public record DraftResponse(List<DraftSectionResponse> sections) {

    /**
     * Maps a {@link DraftConfig} domain object to this response DTO.
     *
     * @param config the domain draft configuration; must not be {@code null}
     * @return the response DTO with all sections mapped
     */
    public static DraftResponse from(DraftConfig config) {
        return new DraftResponse(
                config.getSections().stream().map(DraftSectionResponse::from).toList());
    }
}
