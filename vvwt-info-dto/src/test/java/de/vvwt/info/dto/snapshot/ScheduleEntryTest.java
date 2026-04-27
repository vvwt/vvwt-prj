package de.vvwt.info.dto.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import de.vvwt.info.dto.snapshot.ScheduleEntry;
import de.vvwt.info.dto.snapshot.ScheduleEntry.Match;
import de.vvwt.info.dto.snapshot.ScheduleEntry.Pause;
import de.vvwt.info.dto.snapshot.ScheduleEntry.SpecialAppointment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC2 (testing) — polymorphic discriminator dispatch for sealed ScheduleEntry.
 * Match, SpecialAppointment, Pause subtypes deserialize from discriminator-tagged JSON.
 * Unknown discriminator produces InvalidTypeIdException.
 *
 * <p>DEC-22 Iron Law: this test was written BEFORE ScheduleEntry.java existed (RED state).
 *
 * <p>Story: E38S02.
 */
class ScheduleEntryTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
    }

    @Test
    void match_roundTrip() throws Exception {
        // AC1, AC2 — Match round-trip via discriminator
        Match entry = new Match("match-1", "Team A", "Team B", 1);
        String json = mapper.writeValueAsString(entry);
        ScheduleEntry deserialized = mapper.readValue(json, ScheduleEntry.class);
        assertThat(deserialized).isInstanceOf(Match.class);
        assertThat(deserialized).isEqualTo(entry);
    }

    @Test
    void specialAppointment_roundTrip() throws Exception {
        SpecialAppointment entry = new SpecialAppointment("spa-1", "Award Ceremony");
        String json = mapper.writeValueAsString(entry);
        ScheduleEntry deserialized = mapper.readValue(json, ScheduleEntry.class);
        assertThat(deserialized).isInstanceOf(SpecialAppointment.class);
        assertThat(((SpecialAppointment) deserialized).title()).isEqualTo("Award Ceremony");
    }

    @Test
    void pause_roundTrip() throws Exception {
        Pause entry = new Pause("pause-1", "Lunch break");
        String json = mapper.writeValueAsString(entry);
        ScheduleEntry deserialized = mapper.readValue(json, ScheduleEntry.class);
        assertThat(deserialized).isInstanceOf(Pause.class);
    }

    @Test
    void unknownDiscriminator_throwsInvalidTypeIdException() throws Exception {
        // AC2 — unknown discriminator → InvalidTypeIdException (NOT silent fallback)
        String json = "{\"type\":\"UNKNOWN_SCHEDULE_ENTRY\",\"id\":\"x\"}";
        assertThatThrownBy(() -> mapper.readValue(json, ScheduleEntry.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void discriminatorTagInSerializedJson() throws Exception {
        Match entry = new Match("m1", "A", "B", 1);
        String json = mapper.writeValueAsString(entry);
        assertThat(json).contains("\"type\"");
        assertThat(json).contains("\"MATCH\"");
    }
}
