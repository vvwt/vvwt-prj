package de.vvwt.tm.tournament.internal.dto.draft;

import de.vvwt.tm.tournament.draft.DistributionMode;
import de.vvwt.tm.tournament.draft.GameMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * REST request DTO for a single draft section.
 *
 * <p>Jakarta Validation provides REST-layer validation. Domain-layer validation is performed by
 * {@link de.vvwt.tm.tournament.draft.DraftSection#validate()} and {@link
 * de.vvwt.tm.tournament.draft.DraftSection#validateBreaks(int)}.
 *
 * <p>Inventory: E21S01 line 438. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * <h2>E51S15 — distributionMode field</h2>
 *
 * <p>{@code distributionMode} is optional in the request (may be absent/null). The domain class
 * {@link de.vvwt.tm.tournament.draft.DraftSection} defaults null to {@link
 * de.vvwt.tm.tournament.draft.DistributionMode#SEQUENTIAL}. When present, the value is forwarded
 * as-is; domain validation rejects unrecognized values.
 *
 * <h2>E51S20 — gameMode and distributionMode as type-safe enums</h2>
 *
 * <p>{@link #gameMode} migrated from {@code String} to {@link GameMode} enum. The {@code @Pattern}
 * + {@code @NotBlank} annotations are removed — Jackson's {@link GameMode#fromWireFormat(String)}
 * rejects unknown wire-format values with {@link
 * com.fasterxml.jackson.databind.exc.InvalidFormatException}, which Spring MVC translates to HTTP
 * 400 at the deserialization boundary.
 *
 * <p>{@link #distributionMode} migrated to {@link DistributionMode} enum (nullable — absent/null in
 * the request defaults to {@link DistributionMode#SEQUENTIAL} in the domain class).
 *
 * @see DraftRequest
 * @see DraftBreakRequest
 * @see GameMode
 * @see DistributionMode
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S15">E51S15 — distributionMode feature</a>
 * @see <a href="E51S20">E51S20 — gameMode/distributionMode String→Enum migration</a>
 */
public record DraftSectionRequest(
        @NotNull @Min(value = 1, message = "sectionNumber must be ≥ 1") Integer sectionNumber,
        @NotBlank
                @Pattern(
                        regexp = "team_number|placement_group|group_placement",
                        message =
                                "sortType must be one of: team_number, placement_group,"
                                        + " group_placement")
                String sortType,
        @NotNull @Min(value = 1, message = "groupCount must be ≥ 1") Integer groupCount,
        /**
         * Game mode for this phase. Required; deserialized via {@link
         * GameMode#fromWireFormat(String)} — unknown wire-format values are rejected at the Jackson
         * deserialization boundary with HTTP 400 (AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE, E51S20).
         *
         * @see GameMode
         */
        @NotNull GameMode gameMode,
        @NotNull @Min(value = 0, message = "lapBreakTimeMinutes must be ≥ 0")
                Integer lapBreakTimeMinutes,
        @NotNull @Min(value = 0, message = "sectionBreakTimeMinutes must be ≥ 0")
                Integer sectionBreakTimeMinutes,
        @NotNull @Min(value = 1, message = "lapTimeMinutes must be > 0") Integer lapTimeMinutes,
        @NotNull @Min(value = 1, message = "setQuantity must be ≥ 1") Integer setQuantity,
        /**
         * Optional intra-phase breaks. May be {@code null} (treated as empty list). Each break is
         * independently validated.
         */
        @Valid List<DraftBreakRequest> breaks,
        /**
         * Team distribution algorithm for Phase-1 avatar assignment. Optional — absent/null
         * defaults to {@link DistributionMode#SEQUENTIAL} in the domain class. When present, must
         * be one of the known wire-format values; unknown values are rejected at the Jackson
         * deserialization boundary with HTTP 400 (AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE, E51S20).
         *
         * @see DistributionMode
         * @see de.vvwt.tm.tournament.draft.DraftSection#getDistributionMode()
         * @see <a href="E51S15">E51S15 — distributionMode feature (sequential default + round-robin
         *     toggle)</a>
         * @see <a href="E51S20">E51S20 — migrated from String to DistributionMode enum</a>
         */
        DistributionMode distributionMode) {}
