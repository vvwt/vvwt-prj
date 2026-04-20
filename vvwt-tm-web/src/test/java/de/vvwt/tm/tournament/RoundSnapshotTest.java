package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RED — RoundSnapshot entity unit test (AC-TDD-RoundSnapshot).
 *
 * <p>Tests construction and field accessors for the {@link RoundSnapshot} aggregate entity.
 *
 * @see RoundSnapshot
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 181)</a>
 */
class RoundSnapshotTest {

    /** AC-TDD-RoundSnapshot: RoundSnapshot can be constructed with all mandatory fields. */
    @Test
    void roundSnapshot_withValidFields_constructsSuccessfully() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        String payload = "{\"lapNumber\":3,\"standings\":[]}";

        RoundSnapshot snapshot =
                new RoundSnapshot(id, tenantId, tournamentId, phaseId, 3, payload, null);

        assertThat(snapshot.getId()).isEqualTo(id);
        assertThat(snapshot.getTenantId()).isEqualTo(tenantId);
        assertThat(snapshot.getTournamentId()).isEqualTo(tournamentId);
        assertThat(snapshot.getPhaseId()).isEqualTo(phaseId);
        assertThat(snapshot.getLapNumber()).isEqualTo(3);
        assertThat(snapshot.getSnapshotPayload()).isEqualTo(payload);
        assertThat(snapshot.getCreatedAt()).isNull();
    }

    /** AC-TDD-RoundSnapshot: default constructor produces a mutable instance for Spring Data JDBC. */
    @Test
    void roundSnapshot_defaultConstructor_producesAMutableInstance() {
        RoundSnapshot snapshot = new RoundSnapshot();
        UUID id = UUID.randomUUID();
        snapshot.setId(id);
        assertThat(snapshot.getId()).isEqualTo(id);
    }

    /** AC-TDD-RoundSnapshot: createdAt can be set to a timestamp. */
    @Test
    void roundSnapshot_createdAt_canBeSet() {
        RoundSnapshot snapshot = new RoundSnapshot();
        LocalDateTime now = LocalDateTime.now();
        snapshot.setCreatedAt(now);
        assertThat(snapshot.getCreatedAt()).isEqualTo(now);
    }
}
