package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeviceLimitErrorResponse} JSON shape (E21S06,
 * AC-TDD-DeviceLimitErrorResponse).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link DeviceLimitErrorResponse} at {@code
 * de.vvwt.tm.tournament.internal.DeviceLimitErrorResponse} did not exist at commit time —
 * satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>JSON shape carries: errorCode, configuredLimit, currentCount
 *   <li>Jackson serialization round-trip
 *   <li>errorCode, configuredLimit, currentCount are non-null after construction
 * </ul>
 *
 * @see DeviceLimitErrorResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 421)</a>
 */
@DisplayName("DeviceLimitErrorResponse JSON shape — E21S06 AC-TDD-DeviceLimitErrorResponse")
class DeviceLimitErrorResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("serializes errorCode, configuredLimit, currentCount fields")
    void serializes_errorCode_configuredLimit_currentCount() throws Exception {
        DeviceLimitErrorResponse response =
                new DeviceLimitErrorResponse("DEVICE_LIMIT_EXCEEDED", 10, 10L);

        String json = mapper.writeValueAsString(response);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("errorCode")).as("JSON must contain errorCode field").isTrue();
        assertThat(node.get("errorCode").asText()).isEqualTo("DEVICE_LIMIT_EXCEEDED");

        assertThat(node.has("configuredLimit"))
                .as("JSON must contain configuredLimit field")
                .isTrue();
        assertThat(node.get("configuredLimit").asInt()).isEqualTo(10);

        assertThat(node.has("currentCount")).as("JSON must contain currentCount field").isTrue();
        assertThat(node.get("currentCount").asLong()).isEqualTo(10L);
    }

    @Test
    @DisplayName("getters return correct values")
    void gettersReturnCorrectValues() {
        DeviceLimitErrorResponse response =
                new DeviceLimitErrorResponse("DEVICE_LIMIT_EXCEEDED", 5, 6L);

        assertThat(response.getErrorCode()).isEqualTo("DEVICE_LIMIT_EXCEEDED");
        assertThat(response.getConfiguredLimit()).isEqualTo(5);
        assertThat(response.getCurrentCount()).isEqualTo(6L);
    }
}
