package de.vvwt.tm.tournament.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

/**
 * Type-safe representation of the {@code distributionMode} field in a {@link DraftSection}.
 *
 * <p>Replaces raw {@code String} comparisons (e.g., {@code "round_robin".equals(distributionMode)})
 * with compile-time-safe enum identity checks (e.g., {@code distributionMode ==
 * DistributionMode.ROUND_ROBIN}).
 *
 * <h2>Wire-format preservation (AC-TEST-JSON-WIRE-FORMAT-PRESERVED-*, E51S20)</h2>
 *
 * <p>Jackson serialization round-trips through the external wire-format strings: {@code
 * "sequential"} and {@code "round_robin"}. The application-layer enum names ({@code SEQUENTIAL},
 * {@code ROUND_ROBIN}) are NEVER exposed to the JSON API surface.
 *
 * <ul>
 *   <li>{@link #getWireFormat()} annotated with {@link JsonValue} — controls serialization.
 *   <li>{@link #fromWireFormat(String)} annotated with {@link JsonCreator} — controls
 *       deserialization. Throws {@link InvalidFormatException} on unknown values (fail-fast → HTTP
 *       400 per AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE, E51S20).
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 (Q-1a): wire-format tests {@link DistributionModeJsonWireFormatTest} were RED before
 *       this class was authored.
 *   <li>DEC-29: no new compiler warnings.
 *   <li>DEC-30: Spotless-clean.
 *   <li>DEC-55: {@code distributionMode} is sole source of truth for Phase-N team-assignment shape
 *       (DEC-59 § distributionMode-as-source). This enum is the typed carrier of that decision.
 * </ul>
 *
 * @see DraftSection
 * @see <a href="E51S20">E51S20 — String→Enum hygiene sweep (gameMode + distributionMode)</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, Q-1a for wire-format ACs</a>
 * @see <a href="E51S15">E51S15 — distributionMode feature (sequential default + round-robin
 *     toggle)</a>
 * @see <a href="DEC-59">DEC-59 — distributionMode as sole source of truth</a>
 */
public enum DistributionMode {

    /**
     * Sequential distribution: fill Group 1 fully before Group 2 (group = i / positionsPerGroup +
     * 1, position = i % positionsPerGroup + 1). Default value when absent/null in draft_json.
     * Wire-format: {@code "sequential"}.
     */
    SEQUENTIAL("sequential"),

    /**
     * Round-robin distribution: distribute teams one-per-group before advancing to the next
     * position (group = i % groupCount + 1, position = i / groupCount + 1). Wire-format: {@code
     * "round_robin"} (snake_case — preserved from original API design).
     */
    ROUND_ROBIN("round_robin");

    private final String wireFormat;

    DistributionMode(String wireFormat) {
        this.wireFormat = wireFormat;
    }

    /**
     * Returns the JSON wire-format string for this distribution mode.
     *
     * <p>Annotated with {@link JsonValue} — Jackson uses this value when serializing a {@link
     * DistributionMode} field to JSON.
     *
     * @return the wire-format string (e.g., {@code "sequential"}, {@code "round_robin"})
     */
    @JsonValue
    public String getWireFormat() {
        return wireFormat;
    }

    /**
     * Deserializes a {@link DistributionMode} from its JSON wire-format string.
     *
     * <p>Annotated with {@link JsonCreator} — Jackson invokes this factory method when
     * deserializing a JSON string to a {@link DistributionMode} field. Unknown values throw {@link
     * InvalidFormatException}, which Spring MVC translates to HTTP 400 ({@code
     * HttpMessageNotReadableException}).
     *
     * <p>This is the fail-fast validation gate for the {@code distributionMode} deserialization
     * boundary (AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE, E51S20).
     *
     * @param value the wire-format string from JSON; may be {@code null}
     * @return the matching {@link DistributionMode} constant
     * @throws InvalidFormatException if {@code value} does not match any known wire-format string
     */
    @JsonCreator
    public static DistributionMode fromWireFormat(String value) throws InvalidFormatException {
        if (value != null) {
            for (DistributionMode dm : values()) {
                if (dm.wireFormat.equals(value)) {
                    return dm;
                }
            }
        }
        throw new InvalidFormatException(
                null,
                "Unknown distributionMode wire-format value: '"
                        + value
                        + "'. Valid values: sequential, round_robin."
                        + " (AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE, E51S20)",
                value,
                DistributionMode.class);
    }
}
