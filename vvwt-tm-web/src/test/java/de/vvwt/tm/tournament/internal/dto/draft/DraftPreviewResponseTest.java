// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.vvwt.tm.tournament.draft.DraftPreviewSection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftPreviewResponse DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 434.
 *
 * @see DraftPreviewResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftPreviewResponseTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    /** AC-TDD-DTOs: from() factory maps sections and empty timeline. */
    @Test
    void from_withSectionsAndEmptyTimeline_mapsCorrectly() {
        DraftPreviewSection section = new DraftPreviewSection(1, 2, 4, 6, 3, 12, 75);
        DraftPreviewResponse response = DraftPreviewResponse.from(List.of(section), List.of());

        assertThat(response.sections()).hasSize(1);
        assertThat(response.sections().get(0).phaseNumber()).isEqualTo(1);
        assertThat(response.timeline()).isEmpty();
    }

    /** AC-TDD-DTOs: record stores sections and timeline lists. */
    @Test
    void record_withSectionsAndTimeline_storesBoth() {
        DraftPreviewSectionResponse sectionResponse =
                new DraftPreviewSectionResponse(1, 2, 4, 6, 3, 12, 75);
        DraftPreviewResponse response =
                new DraftPreviewResponse(List.of(sectionResponse), List.of());

        assertThat(response.sections()).hasSize(1);
        assertThat(response.timeline()).isEmpty();
    }
}
