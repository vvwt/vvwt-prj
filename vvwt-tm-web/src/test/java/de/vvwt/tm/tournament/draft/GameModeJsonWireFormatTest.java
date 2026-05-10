package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Q-1a RED-first wire-format preservation tests for {@link GameMode}.
 *
 * <p>Demonstrates that JSON round-trip preserves the external wire-format strings ({@code
 * "siegerehrung"}, {@code "roundRobin"}) and that unknown wire-format values fail-fast with a
 * deserialization exception.
 *
 * <p>These tests were RED before {@code GameMode} was authored (AC-TEST-JSON-WIRE-FORMAT-*,
 * E51S20). They drove the {@code @JsonValue}/{@code @JsonCreator} design of the enum.
 *
 * @see GameMode
 * @see <a href="E51S20">E51S20 — String→Enum hygiene sweep</a>
 * @see <a href="DEC-22">DEC-22 — Q-1a fresh-RED-first for wire-format ACs</a>
 */
class GameModeJsonWireFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Deserialization (wire-format String → enum constant)
    // -------------------------------------------------------------------------

    @Test
    void deserializesFromWireFormat_siegerehrung() throws Exception {
        // AC-TEST-JSON-WIRE-FORMAT-PRESERVED-INPUT-RED: "siegerehrung" → SIEGEREHRUNG
        String json = "\"siegerehrung\"";
        GameMode result = mapper.readValue(json, GameMode.class);
        assertThat(result).isEqualTo(GameMode.SIEGEREHRUNG);
    }

    @Test
    void deserializesFromWireFormat_roundRobin() throws Exception {
        // AC-TEST-JSON-WIRE-FORMAT-PRESERVED-INPUT-RED: "roundRobin" → ROUND_ROBIN
        String json = "\"roundRobin\"";
        GameMode result = mapper.readValue(json, GameMode.class);
        assertThat(result).isEqualTo(GameMode.ROUND_ROBIN);
    }

    // -------------------------------------------------------------------------
    // Serialization (enum constant → wire-format String)
    // -------------------------------------------------------------------------

    @Test
    void serializesToWireFormat_siegerehrung() throws Exception {
        // AC-TEST-JSON-WIRE-FORMAT-PRESERVED-OUTPUT-RED: SIEGEREHRUNG → "siegerehrung"
        String json = mapper.writeValueAsString(GameMode.SIEGEREHRUNG);
        assertThat(json).isEqualTo("\"siegerehrung\"");
    }

    @Test
    void serializesToWireFormat_roundRobin() throws Exception {
        // AC-TEST-JSON-WIRE-FORMAT-PRESERVED-OUTPUT-RED: ROUND_ROBIN → "roundRobin"
        String json = mapper.writeValueAsString(GameMode.ROUND_ROBIN);
        assertThat(json).isEqualTo("\"roundRobin\"");
    }

    // -------------------------------------------------------------------------
    // Unknown wire-format value — fail-fast (AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE)
    // -------------------------------------------------------------------------

    @Test
    void deserialize_unknownWireFormat_throwsException() {
        // AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE: unknown values must fail fast, not silently accept
        assertThatThrownBy(() -> mapper.readValue("\"unknown_mode\"", GameMode.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    // -------------------------------------------------------------------------
    // Wire-format accessor
    // -------------------------------------------------------------------------

    @Test
    void getWireFormat_returnsExpectedStrings() {
        assertThat(GameMode.SIEGEREHRUNG.getWireFormat()).isEqualTo("siegerehrung");
        assertThat(GameMode.ROUND_ROBIN.getWireFormat()).isEqualTo("roundRobin");
    }
}
