// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RawPhaseDefSerializer} and {@link RawPhaseDefDeserializer}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S07). Written before production
 * classes.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Roundtrip serialization: {@code serialize(x) → deserialize → equals(x)}
 *   <li>Deserialization of well-formed JSON
 *   <li>DEC-9 rejection: payloads containing UUID fields → IOException
 * </ul>
 *
 * <p>Story: E37S07; AC-RAW-PHASE-DEF-SERIALIZER; DEC-9
 */
class RawPhaseDefSerializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(RawPhaseDef.class, new RawPhaseDefSerializer());
        module.addDeserializer(RawPhaseDef.class, new RawPhaseDefDeserializer());
        mapper.registerModule(module);
    }

    // -------------------------------------------------------------------------
    // Roundtrip serialization test
    // -------------------------------------------------------------------------

    @Test
    void roundtripPreservesValue() throws Exception {
        RawPhaseDef original =
                new RawPhaseDef(
                        2,
                        2,
                        List.of(
                                new RawRow(
                                        List.of(new PositionTuple(0, 0), new PositionTuple(0, 1))),
                                new RawRow(
                                        List.of(
                                                new PositionTuple(1, 0),
                                                new PositionTuple(1, 1)))));

        String json = mapper.writeValueAsString(original);
        RawPhaseDef deserialized = mapper.readValue(json, RawPhaseDef.class);

        assertThat(deserialized.phaseId()).isEqualTo(original.phaseId());
        assertThat(deserialized.rowCount()).isEqualTo(original.rowCount());
        assertThat(deserialized.rows()).hasSize(original.rows().size());
        // verify all positions preserved (order may differ within row — both are sets)
        for (int i = 0; i < original.rows().size(); i++) {
            assertThat(deserialized.rows().get(i).positions())
                    .containsExactlyInAnyOrderElementsOf(original.rows().get(i).positions());
        }
    }

    // -------------------------------------------------------------------------
    // Deserialization of valid JSON
    // -------------------------------------------------------------------------

    @Test
    void deserializesValidJson() throws Exception {
        String json =
                """
                {
                  "phaseId": 1,
                  "rowCount": 1,
                  "rows": [{"positions": [{"group": 0, "pos": 0}, {"group": 0, "pos": 1}]}]
                }
                """;

        RawPhaseDef result = mapper.readValue(json, RawPhaseDef.class);

        assertThat(result.phaseId()).isEqualTo(1);
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).positions())
                .containsExactlyInAnyOrder(new PositionTuple(0, 0), new PositionTuple(0, 1));
    }

    // -------------------------------------------------------------------------
    // DEC-9 enforcement: reject UUID-bearing payloads
    // -------------------------------------------------------------------------

    @Test
    void rejectsDec9ViolatingPayloadWithUuidField() {
        // Payload contains a 'teamId' UUID field — DEC-9 violation
        String uuidViolatingJson =
                """
                {
                  "phaseId": 1,
                  "rowCount": 1,
                  "rows": [{
                    "positions": [{
                      "group": 0,
                      "pos": 0,
                      "teamId": "550e8400-e29b-41d4-a716-446655440000"
                    }]
                  }]
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(uuidViolatingJson, RawPhaseDef.class))
                .hasMessageContaining("DEC-9 violation");
    }

    @Test
    void rejectsDec9ViolatingPayloadWithUuidAtRowLevel() {
        // UUID-shaped field at the row level
        String uuidViolatingJson =
                """
                {
                  "phaseId": 1,
                  "rowCount": 1,
                  "rows": [{
                    "rowId": "550e8400-e29b-41d4-a716-446655440000",
                    "positions": [{"group": 0, "pos": 0}]
                  }]
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(uuidViolatingJson, RawPhaseDef.class))
                .hasMessageContaining("DEC-9 violation");
    }
}
