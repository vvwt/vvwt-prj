package de.vvwt.tm.tournament.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

/**
 * Type-safe representation of the {@code gameMode} field in a {@link DraftSection}.
 *
 * <p>Replaces raw {@code String} comparisons (e.g., {@code "siegerehrung".equals(gameMode)}) with
 * compile-time-safe enum identity checks (e.g., {@code gameMode == GameMode.SIEGEREHRUNG}).
 *
 * <h2>Wire-format preservation (AC-TEST-JSON-WIRE-FORMAT-PRESERVED-*, E51S20)</h2>
 *
 * <p>Jackson serialization round-trips through the external wire-format strings: {@code
 * "siegerehrung"} and {@code "roundRobin"}. The application-layer enum names ({@code SIEGEREHRUNG},
 * {@code ROUND_ROBIN}) are NEVER exposed to the JSON API surface.
 *
 * <ul>
 *   <li>{@link #getWireFormat()} annotated with {@link JsonValue} — controls serialization.
 *   <li>{@link #fromWireFormat(String)} annotated with {@link JsonCreator} — controls
 *       deserialization. Throws {@link InvalidFormatException} on unknown values (fail-fast → HTTP
 *       400 per AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE, E51S20).
 * </ul>
 *
 * <h2>MatchGeneratorRegistry key (option b per E51S20 execution-plan)</h2>
 *
 * <p>The {@link de.vvwt.tm.tournament.MatchGeneratorRegistry} remains String-keyed (via {@link
 * de.vvwt.tm.tournament.MatchGenerator#getBeanId()}). Call-sites that resolve a generator key from
 * a {@code GameMode} use {@link #getWireFormat()} as the lookup key (e.g., {@code
 * registry.get(GameMode.SIEGEREHRUNG.getWireFormat())}). This preserves the registry contract
 * without requiring an amendment to the {@code MatchGenerator} interface.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 (Q-1a): wire-format tests {@link GameModeJsonWireFormatTest} were RED before this
 *       class was authored.
 *   <li>DEC-29: no new compiler warnings (enum is a standard pattern).
 *   <li>DEC-30: Spotless-clean.
 *   <li>DEC-59: DEC text literal {@code 'siegerehrung'} preserved; call-site uses {@code
 *       GameMode.SIEGEREHRUNG}.
 * </ul>
 *
 * @see DraftSection
 * @see DistributionMode
 * @see <a href="E51S20">E51S20 — String→Enum hygiene sweep (gameMode + distributionMode)</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, Q-1a for wire-format ACs</a>
 * @see <a href="DEC-29">DEC-29 — failOnWarning=true</a>
 * @see <a href="DEC-55">DEC-55 — dispatch text preserved at rule level</a>
 * @see <a href="DEC-56">DEC-56 — per-gameMode MatchGenerator dispatch</a>
 * @see <a href="DEC-59">DEC-59 — Clause F siegerehrung activation-guard</a>
 */
public enum GameMode {

    /**
     * Siegerehrung (awards ceremony) phase. No matches are generated; vacuous L1+L2 execution
     * (DEC-55 D-3 + DEC-59 Clause E). Wire-format: {@code "siegerehrung"}.
     */
    SIEGEREHRUNG("siegerehrung"),

    /**
     * Round-robin phase. Every avatar plays every other avatar within its group (DEC-56 L1).
     * Wire-format: {@code "roundRobin"} (camelCase — preserved from original API design).
     */
    ROUND_ROBIN("roundRobin");

    private final String wireFormat;

    GameMode(String wireFormat) {
        this.wireFormat = wireFormat;
    }

    /**
     * Returns the JSON wire-format string for this game mode.
     *
     * <p>Annotated with {@link JsonValue} — Jackson uses this value when serializing a {@link
     * GameMode} field to JSON.
     *
     * @return the wire-format string (e.g., {@code "siegerehrung"}, {@code "roundRobin"})
     */
    @JsonValue
    public String getWireFormat() {
        return wireFormat;
    }

    /**
     * Deserializes a {@link GameMode} from its JSON wire-format string.
     *
     * <p>Annotated with {@link JsonCreator} — Jackson invokes this factory method when
     * deserializing a JSON string to a {@link GameMode} field. Unknown values throw {@link
     * InvalidFormatException}, which Spring MVC translates to HTTP 400 ({@code
     * HttpMessageNotReadableException}).
     *
     * <p>This is the fail-fast validation gate for the {@code gameMode} deserialization boundary
     * (AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE, E51S20).
     *
     * @param value the wire-format string from JSON; may be {@code null}
     * @return the matching {@link GameMode} constant
     * @throws InvalidFormatException if {@code value} does not match any known wire-format string
     */
    @JsonCreator
    public static GameMode fromWireFormat(String value) throws InvalidFormatException {
        if (value != null) {
            for (GameMode gm : values()) {
                if (gm.wireFormat.equals(value)) {
                    return gm;
                }
            }
        }
        throw new InvalidFormatException(
                null,
                "Unknown gameMode wire-format value: '"
                        + value
                        + "'. Valid values: siegerehrung, roundRobin."
                        + " (AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE, E51S20)",
                value,
                GameMode.class);
    }
}
