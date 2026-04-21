package de.vvwt.tm.tournament.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.MatchState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.StaticApplicationContext;

/**
 * Tests for all four new domain-event classes at {@code de.vvwt.tm.tournament.events.*} (E21S09,
 * AC-SPRING-EVENT-CONTRACT, AC-TDD-DeviceRegisteredEvent, AC-TDD-LapAdvancedEvent,
 * AC-TDD-MatchResultChangedEvent, AC-TDD-PhaseStatusChangedEvent, AC-EVENT-NULL-GUARD).
 *
 * <h2>RED state (DEC-22)</h2>
 *
 * <p>This test was committed RED: the four event classes under {@code
 * de.vvwt.tm.tournament.events.*} did not exist at commit time, causing a compile error. This
 * proves the tests were written before the implementation (DEC-22 Iron Law).
 *
 * <h2>Test approach</h2>
 *
 * <p>Uses a minimal {@link StaticApplicationContext} with a {@link
 * SimpleApplicationEventMulticaster} — no full Spring Boot context. This avoids the pre-existing
 * {@code PhaseCrudRepository} context-startup failure on staging during the E21
 * parallel-development phase. The test validates Spring event publish/capture semantics
 * (AC-SPRING-EVENT-CONTRACT) via a real Spring event publication mechanism without requiring a full
 * application context.
 *
 * <h2>AC-SPRING-EVENT-CONTRACT</h2>
 *
 * <p>Each event is published via a real {@link ApplicationEventPublisher} and captured by a real
 * {@code @EventListener}-equivalent registration. Payload integrity is asserted on the captured
 * event object.
 */
@DisplayName(
        "EventPublishCaptureTest — E21S09 AC-SPRING-EVENT-CONTRACT + AC-EVENT-NULL-GUARD (4"
                + " events)")
class EventPublishCaptureTest {

    private ApplicationEventPublisher publisher;
    private final List<Object> captured = new ArrayList<>();

    @BeforeEach
    void setUp() {
        captured.clear();
        StaticApplicationContext ctx = new StaticApplicationContext();
        ctx.refresh();
        // Register a listener for all four event types
        ctx.addApplicationListener(
                event -> {
                    if (event instanceof DeviceRegisteredEvent
                            || event instanceof LapAdvancedEvent
                            || event instanceof MatchResultChangedEvent
                            || event instanceof PhaseStatusChangedEvent) {
                        captured.add(event);
                    }
                });
        publisher = ctx;
    }

    // -----------------------------------------------------------------------
    // DeviceRegisteredEvent
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-DeviceRegisteredEvent: publish → capture with payload integrity")
    void deviceRegisteredEvent_publishAndCapture() {
        UUID deviceId = UUID.randomUUID();
        DeviceRegisteredEvent event = new DeviceRegisteredEvent(this, deviceId);

        publisher.publishEvent(event);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).isInstanceOf(DeviceRegisteredEvent.class);
        DeviceRegisteredEvent capturedEvent = (DeviceRegisteredEvent) captured.get(0);
        assertThat(capturedEvent.getDeviceId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName(
            "AC-EVENT-NULL-GUARD: DeviceRegisteredEvent(null, uuid) → IllegalArgumentException")
    void deviceRegisteredEvent_nullSource_throwsIllegalArgument() {
        UUID deviceId = UUID.randomUUID();
        // Spring's ApplicationEvent(Object source) throws IllegalArgumentException when source=null
        assertThatThrownBy(() -> new DeviceRegisteredEvent(null, deviceId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // LapAdvancedEvent
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-LapAdvancedEvent: publish → capture with payload integrity")
    void lapAdvancedEvent_publishAndCapture() {
        UUID tenantId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        LapAdvancedEvent event =
                new LapAdvancedEvent(this, tenantId, tournamentId, phaseId, 1, 2, correlationId);

        publisher.publishEvent(event);

        assertThat(captured).hasSize(1);
        LapAdvancedEvent capturedEvent = (LapAdvancedEvent) captured.get(0);
        assertThat(capturedEvent.getTenantId()).isEqualTo(tenantId);
        assertThat(capturedEvent.getTournamentId()).isEqualTo(tournamentId);
        assertThat(capturedEvent.getPhaseId()).isEqualTo(phaseId);
        assertThat(capturedEvent.getPreviousLapNumber()).isEqualTo(1);
        assertThat(capturedEvent.getNewLapNumber()).isEqualTo(2);
        assertThat(capturedEvent.getCorrelationId()).isEqualTo(correlationId);
    }

    // -----------------------------------------------------------------------
    // MatchResultChangedEvent
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-MatchResultChangedEvent: publish → capture with payload integrity")
    void matchResultChangedEvent_publishAndCapture() {
        UUID tenantId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        MatchResultChangedEvent event =
                new MatchResultChangedEvent(
                        this,
                        tenantId,
                        tournamentId,
                        phaseId,
                        matchId,
                        MatchState.OPEN,
                        MatchState.FINISHED_WINNER1,
                        "actor-1",
                        0,
                        0,
                        correlationId);

        publisher.publishEvent(event);

        assertThat(captured).hasSize(1);
        MatchResultChangedEvent capturedEvent = (MatchResultChangedEvent) captured.get(0);
        assertThat(capturedEvent.getMatchId()).isEqualTo(matchId);
        assertThat(capturedEvent.getPreviousState()).isEqualTo(MatchState.OPEN);
        assertThat(capturedEvent.getNewState()).isEqualTo(MatchState.FINISHED_WINNER1);
        assertThat(capturedEvent.getActorId()).isEqualTo("actor-1");
        assertThat(capturedEvent.getCorrelationId()).isEqualTo(correlationId);
    }

    // -----------------------------------------------------------------------
    // PhaseStatusChangedEvent
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-PhaseStatusChangedEvent: publish → capture with payload integrity")
    void phaseStatusChangedEvent_publishAndCapture() {
        UUID tenantId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        PhaseStatusChangedEvent event =
                new PhaseStatusChangedEvent(
                        this, tenantId, tournamentId, phaseId, "PENDING", "ACTIVE");

        publisher.publishEvent(event);

        assertThat(captured).hasSize(1);
        PhaseStatusChangedEvent capturedEvent = (PhaseStatusChangedEvent) captured.get(0);
        assertThat(capturedEvent.getPhaseId()).isEqualTo(phaseId);
        assertThat(capturedEvent.getPreviousStatus()).isEqualTo("PENDING");
        assertThat(capturedEvent.getNewStatus()).isEqualTo("ACTIVE");
    }
}
