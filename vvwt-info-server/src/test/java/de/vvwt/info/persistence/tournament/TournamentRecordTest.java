package de.vvwt.info.persistence.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit test for {@link TournamentRecord} (DEC-22 Iron Law, AC1).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC1</a>
 */
class TournamentRecordTest {

    @Test
    void record_fields_accessible() {
        var now = LocalDateTime.now();
        var secret = new byte[32];
        var record =
                new TournamentRecord(
                        "t-1",
                        "tenant-1",
                        "loc-1",
                        "tok-abc",
                        secret,
                        "{\"schemaVersion\":\"1.0\"}",
                        0L,
                        now,
                        null);

        assertThat(record.tournamentId()).isEqualTo("t-1");
        assertThat(record.tenantId()).isEqualTo("tenant-1");
        assertThat(record.locationId()).isEqualTo("loc-1");
        assertThat(record.tournamentToken()).isEqualTo("tok-abc");
        assertThat(record.perTournamentSecret()).isEqualTo(secret);
        assertThat(record.state()).isEqualTo("{\"schemaVersion\":\"1.0\"}");
        assertThat(record.lastAppliedSeq()).isZero();
        assertThat(record.registeredAt()).isEqualTo(now);
        assertThat(record.supersededAt()).isNull();
    }

    @Test
    void record_superseded_tournament() {
        var now = LocalDateTime.now();
        var record =
                new TournamentRecord(
                        "t-2", "tenant-1", "loc-1", "tok-xyz", new byte[32], null, 5L, now, now);
        assertThat(record.supersededAt()).isNotNull();
    }
}
