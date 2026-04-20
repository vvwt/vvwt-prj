package de.vvwt.tm.tournament.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TDD RED-first test for {@link EventMessage} (E21S10, AC-TDD-EventMessage, inventory row 449).
 *
 * <p>This test was committed RED: {@link EventMessage} at {@code
 * de.vvwt.tm.tournament.internal.web} did not exist at commit time — satisfying the DEC-22 Iron
 * Law.
 *
 * @see EventMessage
 * @see DomainEventBridge
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S10">E21S10 — inventory row 449</a>
 */
@DisplayName("EventMessage — E21S10 AC-TDD-EventMessage")
class EventMessageTest {

    private static final UUID TEST_ENTITY_ID = UUID.randomUUID();
    private static final Instant TEST_TIMESTAMP = Instant.parse("2026-04-20T12:00:00Z");

    @Test
    @DisplayName("Constructor stores eventType, entityId, timestamp; getters return correct values")
    void constructor_storesAllFields() {
        EventMessage msg = new EventMessage("MATCH_RESULT_CHANGED", TEST_ENTITY_ID, TEST_TIMESTAMP);

        assertThat(msg.getEventType()).isEqualTo("MATCH_RESULT_CHANGED");
        assertThat(msg.getEntityId()).isEqualTo(TEST_ENTITY_ID);
        assertThat(msg.getTimestamp()).isEqualTo(TEST_TIMESTAMP);
    }

    @Test
    @DisplayName("Null eventType throws NullPointerException")
    void nullEventType_throwsNpe() {
        assertThatThrownBy(() -> new EventMessage(null, TEST_ENTITY_ID, TEST_TIMESTAMP))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Null entityId throws NullPointerException")
    void nullEntityId_throwsNpe() {
        assertThatThrownBy(() -> new EventMessage("TYPE", null, TEST_TIMESTAMP))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Null timestamp throws NullPointerException")
    void nullTimestamp_throwsNpe() {
        assertThatThrownBy(() -> new EventMessage("TYPE", TEST_ENTITY_ID, null))
                .isInstanceOf(NullPointerException.class);
    }
}
