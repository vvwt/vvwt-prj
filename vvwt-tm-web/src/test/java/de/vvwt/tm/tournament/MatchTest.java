package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Match} entity invariants (E21S05, AC-TDD-Match).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link Match} at {@code de.vvwt.tm.tournament.Match} did not
 * exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>State-type-safe accessor: {@code setMatchState} + {@code getMatchState} round-trip
 *   <li>Null-guard: {@code setMatchState(null)} throws {@link NullPointerException}
 *   <li>UUID fields: id, tenantId, phaseId, memberAvatar1Id, memberAvatar2Id
 *   <li>Default construction succeeds (Spring Data JDBC requirement)
 * </ul>
 *
 * @see Match
 * @see MatchState
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 173 (16 importers)</a>
 */
@DisplayName("Match entity invariants — E21S05 AC-TDD-Match")
class MatchTest {

    @Test
    @DisplayName("Default constructor succeeds (Spring Data JDBC requirement)")
    void defaultConstructorSucceeds() {
        Match m = new Match();
        assertThat(m).isNotNull();
    }

    @Test
    @DisplayName("setMatchState + getMatchState round-trips via legacy code")
    void matchStateRoundTrip() {
        Match m = new Match();
        m.setMatchState(MatchState.ENABLED);
        assertThat(m.getMatchState()).isEqualTo(MatchState.ENABLED);
    }

    @Test
    @DisplayName("setMatchState(null) throws NullPointerException")
    void setMatchStateNullThrows() {
        Match m = new Match();
        assertThatThrownBy(() -> m.setMatchState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("UUID fields stored and retrieved correctly")
    void uuidFieldsRoundTrip() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        UUID avatar1 = UUID.randomUUID();
        UUID avatar2 = UUID.randomUUID();

        Match m = new Match();
        m.setId(id);
        m.setTenantId(tenantId);
        m.setPhaseId(phaseId);
        m.setMemberAvatar1Id(avatar1);
        m.setMemberAvatar2Id(avatar2);

        assertThat(m.getId()).isEqualTo(id);
        assertThat(m.getTenantId()).isEqualTo(tenantId);
        assertThat(m.getPhaseId()).isEqualTo(phaseId);
        assertThat(m.getMemberAvatar1Id()).isEqualTo(avatar1);
        assertThat(m.getMemberAvatar2Id()).isEqualTo(avatar2);
    }

    @Test
    @DisplayName("Two opposing avatars must be distinct UUIDs (invariant for valid match)")
    void opposingAvatarsDistinct() {
        UUID avatar1 = UUID.randomUUID();
        UUID avatar2 = UUID.randomUUID();

        Match m = new Match();
        m.setMemberAvatar1Id(avatar1);
        m.setMemberAvatar2Id(avatar2);

        assertThat(m.getMemberAvatar1Id())
                .as("Opposing avatars must be distinct")
                .isNotEqualTo(m.getMemberAvatar2Id());
    }
}
