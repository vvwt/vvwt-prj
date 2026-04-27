package de.vvwt.info.dto.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.snapshot.ScheduleEntry.Match;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC1 (testing) — JSON round-trip for TournamentSnapshot.
 * AC10 (error-handling) — snapshot responses are envelope-wrapped (no "MAY embed" ambiguity).
 *
 * <p>DEC-22 Iron Law: this test was written BEFORE TournamentSnapshot.java existed (RED state).
 *
 * <p>Story: E38S02.
 */
class TournamentSnapshotTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
    }

    @Test
    void tournamentSnapshot_roundTrip() throws Exception {
        // AC1 — round-trip test: writeValueAsString then readValue produces equals-identical instance
        List<ScheduleEntry> entries =
                List.of(new Match("match-1", "Team A", "Team B", 1));
        TournamentSnapshot snapshot =
                new TournamentSnapshot("tournament-uuid-1", "tenant-uuid-1", 1L, entries);
        String json = mapper.writeValueAsString(snapshot);
        TournamentSnapshot deserialized = mapper.readValue(json, TournamentSnapshot.class);
        assertThat(deserialized).isEqualTo(snapshot);
    }

    @Test
    void snapshotIsEnvelopeWrapped() throws Exception {
        // AC10 — snapshot responses are envelope-wrapped unconditionally
        List<ScheduleEntry> entries = List.of(new Pause("pause-1", "Half time"));
        TournamentSnapshot snapshot =
                new TournamentSnapshot("t-1", "tenant-1", 2L, entries);
        Envelope<TournamentSnapshot> envelope = new Envelope<>(Envelope.SCHEMA_VERSION, snapshot);
        String json = mapper.writeValueAsString(envelope);
        assertThat(json).contains("\"schemaVersion\"");
        assertThat(json).contains("\"1.0\"");
        assertThat(json).contains("\"payload\"");
        // Deserialization
        @SuppressWarnings("unchecked")
        Envelope<TournamentSnapshot> deserialized =
                mapper.readValue(
                        json,
                        mapper.getTypeFactory()
                                .constructParametricType(Envelope.class, TournamentSnapshot.class));
        assertThat(deserialized.payload()).isEqualTo(snapshot);
    }
}
