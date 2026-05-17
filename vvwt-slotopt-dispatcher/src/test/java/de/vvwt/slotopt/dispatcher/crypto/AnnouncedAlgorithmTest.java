// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnnouncedAlgorithm}.
 *
 * <p>RED-first per DEC-22 Iron Law / AC-TDD-RED-FIRST-EVIDENCE / AC-ANNOUNCED-ALGORITHM-RECORD.
 * Written before {@link AnnouncedAlgorithm} exists — compilation fails until Step 1b.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>Record constructor + accessor contract
 *   <li>JSON wire shape (snake_case via {@code @JsonProperty}): algorithm_id, display_name,
 *       deprecation_date, parameters
 *   <li>Null-field serialization (null → JSON null)
 * </ul>
 *
 * <p>Story: E40S02; AC-ANNOUNCED-ALGORITHM-RECORD
 */
class AnnouncedAlgorithmTest {

    // -------------------------------------------------------------------------
    // Record accessor contract
    // -------------------------------------------------------------------------

    @Test
    void recordAccessorsReturnConstructorValues() {
        LocalDate deprecationDate = LocalDate.of(2026, 12, 31);
        Map<String, Object> params = Map.of("parameter_set", "ML-DSA-65");

        AnnouncedAlgorithm alg =
                new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", deprecationDate, params);

        assertThat(alg.algorithmId()).isEqualTo("ML-DSA-65");
        assertThat(alg.displayName()).isEqualTo("ML-DSA-65");
        assertThat(alg.deprecationDate()).isEqualTo(deprecationDate);
        assertThat(alg.parameters()).isEqualTo(params);
    }

    @Test
    void recordWithNullDeprecationDateAndNullParameters() {
        AnnouncedAlgorithm alg = new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null);

        assertThat(alg.algorithmId()).isEqualTo("Ed25519");
        assertThat(alg.displayName()).isEqualTo("Ed25519");
        assertThat(alg.deprecationDate()).isNull();
        assertThat(alg.parameters()).isNull();
    }

    // -------------------------------------------------------------------------
    // JSON wire shape — DEC-43 D1 snake_case contract
    // -------------------------------------------------------------------------

    private static ObjectMapper jsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Explicitly register JavaTimeModule for LocalDate serialization (E42S01: SB 4.x uses
        // Jackson 3.x as primary; findAndRegisterModules() may not find Jackson 2.x jsr310
        // module on the classpath — explicit registration is more reliable).
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // ISO-8601 strings
        return mapper;
    }

    @Test
    void jsonWireShapeIsSnakeCaseWithNullFields() throws Exception {
        ObjectMapper mapper = jsonMapper();

        AnnouncedAlgorithm alg = new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null);
        String json = mapper.writeValueAsString(alg);

        // Must contain snake_case field names per DEC-43 D1
        assertThat(json).contains("\"algorithm_id\"");
        assertThat(json).contains("\"display_name\"");
        assertThat(json).contains("\"deprecation_date\"");
        assertThat(json).contains("\"parameters\"");
        // Null fields must serialize as JSON null (not omitted)
        assertThat(json).contains("\"deprecation_date\":null");
        assertThat(json).contains("\"parameters\":null");
    }

    @Test
    void jsonWireShapeForV1Ed25519IsExact() throws Exception {
        ObjectMapper mapper = jsonMapper();

        AnnouncedAlgorithm alg = new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null);
        String json = mapper.writeValueAsString(alg);

        // AC-V1-WIRE-SHAPE exact JSON for the record itself (list wrapped by controller/IT)
        assertThat(json)
                .isEqualTo(
                        "{\"algorithm_id\":\"Ed25519\","
                                + "\"display_name\":\"Ed25519\","
                                + "\"deprecation_date\":null,"
                                + "\"parameters\":null}");
    }

    @Test
    void jsonWireShapeWithDeprecationDateAndParameters() throws Exception {
        ObjectMapper mapper = jsonMapper();

        AnnouncedAlgorithm alg =
                new AnnouncedAlgorithm(
                        "ML-DSA-65",
                        "ML-DSA-65",
                        LocalDate.of(2030, 1, 1),
                        Map.of("parameter_set", "ML-DSA-65"));
        String json = mapper.writeValueAsString(alg);

        assertThat(json).contains("\"algorithm_id\":\"ML-DSA-65\"");
        assertThat(json).contains("\"display_name\":\"ML-DSA-65\"");
        assertThat(json).contains("\"deprecation_date\":\"2030-01-01\"");
        assertThat(json).contains("\"parameter_set\"");
    }
}
