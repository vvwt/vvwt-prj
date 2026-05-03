package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RED — PhaseBreak entity unit test (AC-TDD-PhaseBreak).
 *
 * <p>Tests construction and field accessors for the {@link PhaseBreak} aggregate entity.
 *
 * @see PhaseBreak
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 178)</a>
 */
class PhaseBreakTest {

    /** AC-TDD-PhaseBreak: PhaseBreak can be constructed with all mandatory fields. */
    @Test
    void phaseBreak_withValidFields_constructsSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();

        PhaseBreak phaseBreak = new PhaseBreak(id, phaseId, 2, 30, "Mittagspause");

        assertThat(phaseBreak.getId()).isEqualTo(id);
        assertThat(phaseBreak.getPhaseId()).isEqualTo(phaseId);
        assertThat(phaseBreak.getAfterLapNumber()).isEqualTo(2);
        assertThat(phaseBreak.getDurationMinutes()).isEqualTo(30);
        assertThat(phaseBreak.getLabel()).isEqualTo("Mittagspause");
    }

    /** AC-TDD-PhaseBreak: label may be null (optional field per schema). */
    @Test
    void phaseBreak_withNullLabel_constructsSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();

        PhaseBreak phaseBreak = new PhaseBreak(id, phaseId, 1, 15, null);

        assertThat(phaseBreak.getLabel()).isNull();
    }

    /** AC-TDD-PhaseBreak: default constructor produces a mutable instance for Spring Data JDBC. */
    @Test
    void phaseBreak_defaultConstructor_producesAMutableInstance() {
        PhaseBreak phaseBreak = new PhaseBreak();
        UUID id = UUID.randomUUID();
        phaseBreak.setId(id);
        assertThat(phaseBreak.getId()).isEqualTo(id);
    }
}
