// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TDD unit tests for {@link PartialScoreUpdatedEvent} (E65S02, AC2, AC4, DEC-22).
 *
 * <p>Written RED-first: the production class did not exist at test-commit time per DEC-22 Iron Law.
 */
class PartialScoreUpdatedEventTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MATCH_ID = UUID.randomUUID();
    private static final Object SOURCE = new Object();

    @Test
    void constructorStoresAllFields() {
        PartialScoreUpdatedEvent event = new PartialScoreUpdatedEvent(SOURCE, TENANT_ID, MATCH_ID);

        assertThat(event.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(event.getMatchId()).isEqualTo(MATCH_ID);
    }

    @Test
    void getSourceReturnsConstructorArg() {
        PartialScoreUpdatedEvent event = new PartialScoreUpdatedEvent(SOURCE, TENANT_ID, MATCH_ID);

        assertThat(event.getSource()).isSameAs(SOURCE);
    }
}
