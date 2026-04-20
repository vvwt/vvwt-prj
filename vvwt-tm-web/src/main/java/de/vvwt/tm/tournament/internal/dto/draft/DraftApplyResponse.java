package de.vvwt.tm.tournament.internal.dto.draft;

import java.util.List;
import java.util.UUID;

/**
 * REST response body for the draft apply endpoint.
 *
 * <p>Returns the list of Phase IDs created by the apply operation, ordered by phase sequence
 * number.
 *
 * <p>Inventory: E21S01 line 431. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see de.vvwt.tm.tournament.DraftController
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public record DraftApplyResponse(List<UUID> phaseIds) {}
