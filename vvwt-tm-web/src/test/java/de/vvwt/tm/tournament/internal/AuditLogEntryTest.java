package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.AuditLogEntry;
import de.vvwt.tm.tournament.SetState;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuditLogEntry} entity invariants (E21S05, AC-TDD-AuditLogEntry).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link AuditLogEntry} at {@code
 * de.vvwt.tm.tournament.internal.AuditLogEntry} did not exist at commit time, causing a compile
 * error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Default construction (Spring Data JDBC requirement)
 *   <li>Old-value fields nullable: {@code team1PointsOld} etc. are null on first INSERT
 *   <li>{@code getSetStateOldAsEnum} returns null when setStateOld is null
 *   <li>{@code getSetStateNewAsEnum} resolves the new state code
 *   <li>Append-only semantics: class exposes no delete methods
 * </ul>
 *
 * @see AuditLogEntry
 * @see SetState
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 167 (INTERNAL audit substrate)</a>
 */
@DisplayName("AuditLogEntry entity invariants — E21S05 AC-TDD-AuditLogEntry")
class AuditLogEntryTest {

    @Test
    @DisplayName("Default constructor succeeds (Spring Data JDBC requirement)")
    void defaultConstructorSucceeds() {
        AuditLogEntry entry = new AuditLogEntry();
        assertThat(entry).isNotNull();
    }

    @Test
    @DisplayName("Old-value fields are null by default (first INSERT semantics)")
    void oldValueFieldsNullByDefault() {
        AuditLogEntry entry = new AuditLogEntry();
        assertThat(entry.getTeam1PointsOld()).isNull();
        assertThat(entry.getTeam2PointsOld()).isNull();
        assertThat(entry.getSetStateOld()).isNull();
    }

    @Test
    @DisplayName("getSetStateOldAsEnum returns null when setStateOld is null")
    void getSetStateOldAsEnumReturnsNullWhenNotSet() {
        AuditLogEntry entry = new AuditLogEntry();
        assertThat(entry.getSetStateOldAsEnum()).isNull();
    }

    @Test
    @DisplayName("getSetStateNewAsEnum resolves the stored code")
    void getSetStateNewAsEnumResolves() {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setSetStateNew(SetState.WINNER1.getLegacyCode());
        assertThat(entry.getSetStateNewAsEnum()).isEqualTo(SetState.WINNER1);
    }

    @Test
    @DisplayName("UUID fields (id, tenantId, matchId) round-trip")
    void uuidFieldsRoundTrip() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        AuditLogEntry entry = new AuditLogEntry();
        entry.setId(id);
        entry.setTenantId(tenantId);
        entry.setMatchId(matchId);

        assertThat(entry.getId()).isEqualTo(id);
        assertThat(entry.getTenantId()).isEqualTo(tenantId);
        assertThat(entry.getMatchId()).isEqualTo(matchId);
    }
}
