// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.referee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RefereePreferenceConfig} (AC-TDD-RefereePreferenceConfig, E21S08).
 *
 * <p>Verifies: parse null → EMPTY; parse blank → EMPTY; parse valid JSON → correct preferred list;
 * malformed JSON → EMPTY; non-UUID values skipped with WARN.
 *
 * <p>Source: inventory row 278 — {@code
 * de.vvwt.tm.tournament.internal.referee.RefereePreferenceConfig}.
 *
 * <p>Related DECs: DEC-22 (TDD Iron Law).
 */
class RefereePreferenceConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // parse null → EMPTY
    // -------------------------------------------------------------------------

    @Test
    void parse_null_returnsEmpty() {
        RefereePreferenceConfig config = RefereePreferenceConfig.parse(null, objectMapper);
        assertThat(config).isSameAs(RefereePreferenceConfig.EMPTY);
        assertThat(config.isEmpty()).isTrue();
    }

    // -------------------------------------------------------------------------
    // parse blank → EMPTY
    // -------------------------------------------------------------------------

    @Test
    void parse_blank_returnsEmpty() {
        RefereePreferenceConfig config = RefereePreferenceConfig.parse("  ", objectMapper);
        assertThat(config.isEmpty()).isTrue();
    }

    // -------------------------------------------------------------------------
    // parse empty JSON object → EMPTY (no "preferred" key)
    // -------------------------------------------------------------------------

    @Test
    void parse_emptyJsonObject_returnsEmpty() {
        RefereePreferenceConfig config = RefereePreferenceConfig.parse("{}", objectMapper);
        assertThat(config.isEmpty()).isTrue();
    }

    // -------------------------------------------------------------------------
    // parse valid JSON with preferred list → correct UUIDs
    // -------------------------------------------------------------------------

    @Test
    void parse_validJson_returnsPreferredList() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String json = "{\"preferred\":[\"" + id1 + "\",\"" + id2 + "\"]}";

        RefereePreferenceConfig config = RefereePreferenceConfig.parse(json, objectMapper);

        assertThat(config.getPreferred()).containsExactly(id1, id2);
    }

    // -------------------------------------------------------------------------
    // parse malformed JSON → EMPTY (no exception thrown)
    // -------------------------------------------------------------------------

    @Test
    void parse_malformedJson_returnsEmpty() {
        RefereePreferenceConfig config =
                RefereePreferenceConfig.parse("{not valid json", objectMapper);
        assertThat(config.isEmpty()).isTrue();
    }

    // -------------------------------------------------------------------------
    // parse JSON with non-UUID values → skipped, no crash
    // -------------------------------------------------------------------------

    @Test
    void parse_nonUuidValues_skipped() {
        UUID validId = UUID.randomUUID();
        String json = "{\"preferred\":[\"not-a-uuid\",\"" + validId + "\"]}";

        RefereePreferenceConfig config = RefereePreferenceConfig.parse(json, objectMapper);

        assertThat(config.getPreferred()).containsExactly(validId);
    }

    // -------------------------------------------------------------------------
    // parse JSON where "preferred" is not an array → EMPTY
    // -------------------------------------------------------------------------

    @Test
    void parse_preferredNotArray_returnsEmpty() {
        String json = "{\"preferred\":\"roundRobin\"}";
        RefereePreferenceConfig config = RefereePreferenceConfig.parse(json, objectMapper);
        assertThat(config.isEmpty()).isTrue();
    }

    // -------------------------------------------------------------------------
    // getPreferred is unmodifiable
    // -------------------------------------------------------------------------

    @Test
    void getPreferred_isUnmodifiable() {
        UUID id = UUID.randomUUID();
        RefereePreferenceConfig config = new RefereePreferenceConfig(List.of(id));

        assertThatThrownBy(() -> config.getPreferred().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // Constructor null preferred → NPE
    // -------------------------------------------------------------------------

    @Test
    void constructor_nullPreferred_throwsNPE() {
        assertThatThrownBy(() -> new RefereePreferenceConfig(null))
                .isInstanceOf(NullPointerException.class);
    }
}
