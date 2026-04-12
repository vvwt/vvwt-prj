package de.vvwt.tm.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JacksonConfig} serialization rules (AC2, E05S03).
 *
 * <p>Verifies:
 * <ul>
 *   <li>AC2: {@link LocalDateTime} serializes as ISO-8601 string, not a timestamp array</li>
 *   <li>AC2: {@link Instant} serializes as ISO-8601 string</li>
 *   <li>AC2: enum values serialize as name strings, not ordinal integers</li>
 *   <li>AC2: {@code null} fields are excluded from the serialized JSON</li>
 * </ul>
 *
 * @see JacksonConfig
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 */
@JsonTest
@Import(JacksonConfig.class)
class JacksonConfigTest {

    @Autowired
    private ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // AC2 — Dates as ISO-8601 strings
    // -------------------------------------------------------------------------

    @Test
    void localDateTime_serializedAsIso8601String() throws Exception {
        LocalDateTime dt = LocalDateTime.of(2026, 4, 12, 15, 37, 9);
        String json = objectMapper.writeValueAsString(dt);

        // ISO-8601: "2026-04-12T15:37:09" — not an array [2026, 4, 12, 15, 37, 9]
        assertThat(json)
                .as("AC2: LocalDateTime must serialize as ISO-8601 string")
                .isEqualTo("\"2026-04-12T15:37:09\"");
    }

    @Test
    void instant_serializedAsIso8601String() throws Exception {
        // Use Instant.parse to get a known value unambiguously
        Instant instant = Instant.parse("2026-04-12T15:37:09Z");
        String json = objectMapper.writeValueAsString(instant);

        assertThat(json)
                .as("AC2: Instant must serialize as ISO-8601 string")
                .startsWith("\"")
                .endsWith("\"")
                // ISO-8601 format — not a numeric timestamp array
                .contains("2026-04-12T15:37:09");
    }

    // -------------------------------------------------------------------------
    // AC2 — Enums as strings (not ordinals)
    // -------------------------------------------------------------------------

    @Test
    void enum_serializedAsNameString() throws Exception {
        SampleEnum value = SampleEnum.BETA;
        String json = objectMapper.writeValueAsString(value);

        assertThat(json)
                .as("AC2: enum must serialize as name string 'BETA', not ordinal integer '1'")
                .isEqualTo("\"BETA\"");
    }

    // -------------------------------------------------------------------------
    // AC2 — Null fields excluded
    // -------------------------------------------------------------------------

    @Test
    void nullFields_excludedFromJson() throws Exception {
        SampleDto dto = new SampleDto("hello", null);
        String json = objectMapper.writeValueAsString(dto);

        assertThat(json)
                .as("AC2: null field 'optionalField' must be excluded from JSON")
                .doesNotContain("optionalField");
        assertThat(json)
                .contains("\"requiredField\":\"hello\"");
    }

    // -------------------------------------------------------------------------
    // Helper types
    // -------------------------------------------------------------------------

    enum SampleEnum {
        ALPHA, BETA, GAMMA
    }

    static class SampleDto {
        private final String requiredField;
        private final String optionalField; // null → must be excluded

        SampleDto(String requiredField, String optionalField) {
            this.requiredField = requiredField;
            this.optionalField = optionalField;
        }

        public String getRequiredField() { return requiredField; }
        public String getOptionalField() { return optionalField; }
    }
}
