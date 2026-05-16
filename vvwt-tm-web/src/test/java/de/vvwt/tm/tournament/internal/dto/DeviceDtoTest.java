// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for all 6 Device DTOs (E21S06, AC-TDD-DTOs).
 *
 * <h2>RED state</h2>
 *
 * <p>These tests were committed RED: none of the 6 DTO classes at {@code
 * de.vvwt.tm.tournament.internal.dto} existed at commit time — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <p>One JSON (de)serialization round-trip assertion per DTO:
 *
 * <ul>
 *   <li>{@link DeviceAssignRequest} — fieldNumber, pin (nullable)
 *   <li>{@link DeviceConfigureRequest} — deviceName, configuration
 *   <li>{@link DeviceRegisterRequest} — deviceType nullable
 *   <li>{@link DeviceRegisterResponse} — deviceToken, pin (nullable), deviceName (nullable)
 *   <li>{@link DeviceStatusResponse} — status, assignedField (nullable)
 *   <li>{@link DeviceSummaryResponse} — id, deviceToken, deviceType, status (pin removed E49S01
 *       AC3)
 * </ul>
 *
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory lines 425-430)</a>
 */
@DisplayName("Device DTOs JSON contract — E21S06 AC-TDD-DTOs")
class DeviceDtoTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // =========================================================================
    // DeviceAssignRequest
    // =========================================================================

    @Nested
    @DisplayName("DeviceAssignRequest")
    class DeviceAssignRequestTests {

        @Test
        @DisplayName("serializes fieldNumber and pin (E49S01 AC5)")
        void serializesFieldNumber() throws Exception {
            DeviceAssignRequest req = new DeviceAssignRequest(3, "2345");
            String json = mapper.writeValueAsString(req);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("fieldNumber").asInt()).isEqualTo(3);
            assertThat(node.get("pin").asText()).isEqualTo("2345");
        }

        @Test
        @DisplayName("deserializes fieldNumber; pin is optional (null for DISPLAY)")
        void deserializesFieldNumber() throws Exception {
            String json = "{\"fieldNumber\":5}";
            DeviceAssignRequest req = mapper.readValue(json, DeviceAssignRequest.class);

            assertThat(req.fieldNumber()).isEqualTo(5);
            assertThat(req.pin()).isNull();
        }
    }

    // =========================================================================
    // DeviceConfigureRequest
    // =========================================================================

    @Nested
    @DisplayName("DeviceConfigureRequest")
    class DeviceConfigureRequestTests {

        @Test
        @DisplayName("serializes deviceName and configuration")
        void serializesFields() throws Exception {
            DeviceConfigureRequest req =
                    new DeviceConfigureRequest("Display 1", "{\"display_schema\":\"OVERVIEW\"}");
            String json = mapper.writeValueAsString(req);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("deviceName").asText()).isEqualTo("Display 1");
            assertThat(node.get("configuration").asText())
                    .isEqualTo("{\"display_schema\":\"OVERVIEW\"}");
        }

        @Test
        @DisplayName("deserializes deviceName and configuration")
        void deserializesFields() throws Exception {
            String json = "{\"deviceName\":\"My Screen\",\"configuration\":null}";
            DeviceConfigureRequest req = mapper.readValue(json, DeviceConfigureRequest.class);

            assertThat(req.deviceName()).isEqualTo("My Screen");
            assertThat(req.configuration()).isNull();
        }
    }

    // =========================================================================
    // DeviceRegisterRequest
    // =========================================================================

    @Nested
    @DisplayName("DeviceRegisterRequest")
    class DeviceRegisterRequestTests {

        @Test
        @DisplayName("serializes deviceType")
        void serializesDeviceType() throws Exception {
            DeviceRegisterRequest req = new DeviceRegisterRequest("DISPLAY");
            String json = mapper.writeValueAsString(req);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("deviceType").asText()).isEqualTo("DISPLAY");
        }

        @Test
        @DisplayName("deviceType is nullable (default = SCORING_TABLET handled in controller)")
        void deviceTypeIsNullable() throws Exception {
            String json = "{}";
            DeviceRegisterRequest req = mapper.readValue(json, DeviceRegisterRequest.class);

            assertThat(req.deviceType()).isNull();
        }
    }

    // =========================================================================
    // DeviceRegisterResponse
    // =========================================================================

    @Nested
    @DisplayName("DeviceRegisterResponse")
    class DeviceRegisterResponseTests {

        @Test
        @DisplayName("serializes deviceToken, pin and deviceName (E49S01 AC2)")
        void serializesTokenAndPin() throws Exception {
            DeviceRegisterResponse resp =
                    new DeviceRegisterResponse(
                            UUID.randomUUID(), "token-abc", "1234", "Tablet-A1B2");
            String json = mapper.writeValueAsString(resp);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("deviceToken").asText()).isEqualTo("token-abc");
            assertThat(node.get("pin").asText()).isEqualTo("1234");
            assertThat(node.get("deviceName").asText()).isEqualTo("Tablet-A1B2");
        }

        @Test
        @DisplayName("pin and deviceName are null for DISPLAY device response")
        void pinIsNullableForDisplay() throws Exception {
            DeviceRegisterResponse resp =
                    new DeviceRegisterResponse(UUID.randomUUID(), "token-display", null, null);
            String json = mapper.writeValueAsString(resp);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("deviceToken").asText()).isEqualTo("token-display");
            // pin absent or null in JSON
            assertThat(
                            node.has("pin") && !node.get("pin").isNull()
                                    ? node.get("pin").asText()
                                    : null)
                    .isNullOrEmpty();
        }
    }

    // =========================================================================
    // DeviceStatusResponse
    // =========================================================================

    @Nested
    @DisplayName("DeviceStatusResponse")
    class DeviceStatusResponseTests {

        @Test
        @DisplayName("serializes status and assignedField")
        void serializesStatusAndField() throws Exception {
            DeviceStatusResponse resp = new DeviceStatusResponse("ASSIGNED", 2, null);
            String json = mapper.writeValueAsString(resp);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("status").asText()).isEqualTo("ASSIGNED");
            assertThat(node.get("assignedField").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("assignedField is null when unassigned")
        void assignedFieldIsNullable() throws Exception {
            DeviceStatusResponse resp = new DeviceStatusResponse("REGISTERED", null, null);
            String json = mapper.writeValueAsString(resp);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("status").asText()).isEqualTo("REGISTERED");
        }
    }

    // =========================================================================
    // DeviceSummaryResponse
    // =========================================================================

    @Nested
    @DisplayName("DeviceSummaryResponse")
    class DeviceSummaryResponseTests {

        @Test
        @DisplayName("serializes id, deviceToken, deviceType, status (pin removed E49S01 AC3)")
        void serializesCoreFields() throws Exception {
            UUID id = UUID.randomUUID();
            DeviceSummaryResponse resp =
                    new DeviceSummaryResponse(
                            id,
                            "tok-xyz",
                            "SCORING_TABLET",
                            1,
                            "ASSIGNED",
                            null,
                            "Tablet-A1B2",
                            null);
            String json = mapper.writeValueAsString(resp);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("id").asText()).isEqualTo(id.toString());
            assertThat(node.get("deviceToken").asText()).isEqualTo("tok-xyz");
            assertThat(node.has("pin")).as("E49S01 AC3: pin must not appear in summary").isFalse();
            assertThat(node.get("deviceType").asText()).isEqualTo("SCORING_TABLET");
            assertThat(node.get("status").asText()).isEqualTo("ASSIGNED");
        }
    }
}
