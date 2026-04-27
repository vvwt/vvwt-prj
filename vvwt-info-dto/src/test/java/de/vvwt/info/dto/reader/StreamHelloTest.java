package de.vvwt.info.dto.reader;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link StreamHello} (E38S06 AC14).
 *
 * <p>Tests written BEFORE the production record exists per DEC-22 Iron Law.
 */
class StreamHelloTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void record_hasExpectedFields() {
        var hello = new StreamHello(5, "1.0");
        assertThat(hello.pollCadenceSeconds()).isEqualTo(5);
        assertThat(hello.schemaVersion()).isEqualTo("1.0");
    }

    @Test
    void serializes_to_json_with_expected_property_names() throws Exception {
        var hello = new StreamHello(10, "1.0");
        String json = mapper.writeValueAsString(hello);
        assertThat(json).contains("\"poll_cadence_seconds\":10");
        assertThat(json).contains("\"schemaVersion\":\"1.0\"");
    }

    @Test
    void deserializes_from_json() throws Exception {
        String json = "{\"poll_cadence_seconds\":7,\"schemaVersion\":\"1.0\"}";
        var hello = mapper.readValue(json, StreamHello.class);
        assertThat(hello.pollCadenceSeconds()).isEqualTo(7);
        assertThat(hello.schemaVersion()).isEqualTo("1.0");
    }
}
