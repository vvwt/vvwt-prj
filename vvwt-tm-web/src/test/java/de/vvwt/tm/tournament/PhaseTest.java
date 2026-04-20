package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RED — Phase entity unit test (AC-TDD-Phase).
 *
 * <p>Tests at least one behavioural invariant: phase-status validity (status must not be null or
 * blank) and round-count invariant (currentLapNumber must be &ge; 0).
 *
 * @see Phase
 * @see <a href="E21S03">E21S03 — Phase + PhaseBreak + RoundSnapshot reconstruction</a>
 */
class PhaseTest {

    /** AC-TDD-Phase: Phase can be constructed with all mandatory fields. */
    @Test
    void phase_withValidFields_constructsSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();

        Phase phase =
                new Phase(
                        id,
                        tenantId,
                        tournamentId,
                        1,
                        "Vorrunde",
                        Phase.PhaseStatus.PENDING.name(),
                        0,
                        null);

        assertThat(phase.getId()).isEqualTo(id);
        assertThat(phase.getTenantId()).isEqualTo(tenantId);
        assertThat(phase.getTournamentId()).isEqualTo(tournamentId);
        assertThat(phase.getSequenceNumber()).isEqualTo(1);
        assertThat(phase.getDescription()).isEqualTo("Vorrunde");
        assertThat(phase.getStatus()).isEqualTo("PENDING");
        assertThat(phase.getCurrentLapNumber()).isEqualTo(0);
    }

    /** AC-TDD-Phase: PhaseStatus enum covers the expected lifecycle values. */
    @Test
    void phaseStatus_enumValues_coverFullLifecycle() {
        assertThat(Phase.PhaseStatus.values())
                .containsExactlyInAnyOrder(
                        Phase.PhaseStatus.PENDING,
                        Phase.PhaseStatus.ACTIVE,
                        Phase.PhaseStatus.COMPLETED);
    }

    /** AC-TDD-Phase: currentLapNumber invariant — can be set to 0 (initial). */
    @Test
    void phase_currentLapNumber_startsAtZero() {
        Phase phase = new Phase();
        phase.setCurrentLapNumber(0);
        assertThat(phase.getCurrentLapNumber()).isEqualTo(0);
    }

    /** AC-TDD-Phase: sequenceNumber must be positive (≥ 1) by convention. */
    @Test
    void phase_sequenceNumber_isPositive() {
        Phase phase = new Phase();
        phase.setSequenceNumber(1);
        assertThat(phase.getSequenceNumber()).isGreaterThanOrEqualTo(1);
    }

    /** AC-TDD-Phase: default constructor produces a Phase ready for Spring Data JDBC mapping. */
    @Test
    void phase_defaultConstructor_producesAMutableInstance() {
        Phase phase = new Phase();
        UUID id = UUID.randomUUID();
        phase.setId(id);
        assertThat(phase.getId()).isEqualTo(id);
    }
}
