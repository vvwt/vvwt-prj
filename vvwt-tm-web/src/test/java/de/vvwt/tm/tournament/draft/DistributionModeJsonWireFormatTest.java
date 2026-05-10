package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Q-1a RED-first wire-format preservation tests for {@link DistributionMode}.
 *
 * <p>Demonstrates that JSON round-trip preserves the external wire-format strings ({@code
 * "sequential"}, {@code "round_robin"}) and that unknown wire-format values fail-fast with a
 * deserialization exception.
 *
 * <p>These tests were RED before {@code DistributionMode} was authored (AC-TEST-JSON-WIRE-FORMAT-*,
 * E51S20). They drove the {@code @JsonValue}/{@code @JsonCreator} design of the enum.
 *
 * @see DistributionMode
 * @see <a href="E51S20">E51S20 — String→Enum hygiene sweep</a>
 * @see <a href="DEC-22">DEC-22 — Q-1a fresh-RED-first for wire-format ACs</a>
 */
class DistributionModeJsonWireFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Deserialization (wire-format String → enum constant)
    // -------------------------------------------------------------------------

    @Test
    void deserializesFromWireFormat_sequential() throws Exception {
        // AC-TEST-JSON-WIRE-FORMAT-PRESERVED-INPUT-RED: "sequential" → SEQUENTIAL
        String json = "\"sequential\"";
        DistributionMode result = mapper.readValue(json, DistributionMode.class);
        assertThat(result).isEqualTo(DistributionMode.SEQUENTIAL);
    }

    @Test
    void deserializesFromWireFormat_roundRobin() throws Exception {
        // AC-TEST-JSON-WIRE-FORMAT-PRESERVED-INPUT-RED: "round_robin" → ROUND_ROBIN
        String json = "\"round_robin\"";
        DistributionMode result = mapper.readValue(json, DistributionMode.class);
        assertThat(result).isEqualTo(DistributionMode.ROUND_ROBIN);
    }

    // -------------------------------------------------------------------------
    // Serialization (enum constant → wire-format String)
    // -------------------------------------------------------------------------

    @Test
    void serializesToWireFormat_sequential() throws Exception {
        // AC-TEST-JSON-WIRE-FORMAT-PRESERVED-OUTPUT-RED: SEQUENTIAL → "sequential"
        String json = mapper.writeValueAsString(DistributionMode.SEQUENTIAL);
        assertThat(json).isEqualTo("\"sequential\"");
    }

    @Test
    void serializesToWireFormat_roundRobin() throws Exception {
        // AC-TEST-JSON-WIRE-FORMAT-PRESERVED-OUTPUT-RED: ROUND_ROBIN → "round_robin"
        String json = mapper.writeValueAsString(DistributionMode.ROUND_ROBIN);
        assertThat(json).isEqualTo("\"round_robin\"");
    }

    // -------------------------------------------------------------------------
    // Unknown wire-format value — fail-fast (AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE)
    // -------------------------------------------------------------------------

    @Test
    void deserialize_unknownWireFormat_throwsException() {
        // AC-ERROR-UNKNOWN-WIRE-FORMAT-VALUE: unknown values must fail fast, not silently accept
        assertThatThrownBy(() -> mapper.readValue("\"turbo_mode\"", DistributionMode.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
    }

    // -------------------------------------------------------------------------
    // Wire-format accessor
    // -------------------------------------------------------------------------

    @Test
    void getWireFormat_returnsExpectedStrings() {
        assertThat(DistributionMode.SEQUENTIAL.getWireFormat()).isEqualTo("sequential");
        assertThat(DistributionMode.ROUND_ROBIN.getWireFormat()).isEqualTo("round_robin");
    }
}
