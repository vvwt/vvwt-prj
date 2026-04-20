package de.vvwt.tm.tournament.internal.dto.draft;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * REST request DTO for a single intra-phase break within a draft section.
 *
 * <p>Jakarta Validation enforces REST-layer constraints. Domain-layer semantic validation (valid
 * lap position, no duplicates) is performed by {@link
 * de.vvwt.tm.tournament.internal.draft.DraftSection#validateBreaks(int)}.
 *
 * <p>Inventory: E21S01 line 432. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see DraftSectionRequest
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public record DraftBreakRequest(
        @NotNull @Min(value = 1, message = "afterLapNumber must be ≥ 1") Integer afterLapNumber,
        @NotNull @Min(value = 1, message = "durationMinutes must be > 0") Integer durationMinutes,
        /** Optional display label (e.g., "Mittagspause"). May be {@code null} or blank. */
        String label) {}
