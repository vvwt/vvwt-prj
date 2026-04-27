package de.vvwt.info.dto.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import de.vvwt.info.dto.event.DomainEvent;
import de.vvwt.info.dto.event.DomainEvent.MatchResultFinalized;
import de.vvwt.info.dto.event.DomainEvent.RoundCompleted;
import de.vvwt.info.dto.event.DomainEvent.ScheduleAdded;
import de.vvwt.info.dto.event.DomainEvent.ScheduleRemoved;
import de.vvwt.info.dto.event.DomainEvent.ScoreUpdated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC2 (testing) — polymorphic discriminator dispatch for sealed DomainEvent.
 * All 5 sealed subtypes dispatch from discriminator-tagged JSON.
 * Unknown discriminator produces InvalidTypeIdException (NOT silent fallback).
 *
 * <p>DEC-22 Iron Law: this test was written BEFORE DomainEvent.java existed (RED state).
 *
 * <p>Story: E38S02.
 */
class DomainEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
    }

    @Test
    void scoreUpdated_roundTrip() throws Exception {
        // AC1, AC2 — ScoreUpdated round-trip via discriminator
        ScoreUpdated event = new ScoreUpdated("match-1", 2, 1);
        String json = mapper.writeValueAsString(event);
        DomainEvent deserialized = mapper.readValue(json, DomainEvent.class);
        assertThat(deserialized).isInstanceOf(ScoreUpdated.class);
        assertThat(deserialized).isEqualTo(event);
    }

    @Test
    void roundCompleted_roundTrip() throws Exception {
        RoundCompleted event = new RoundCompleted(3);
        String json = mapper.writeValueAsString(event);
        DomainEvent deserialized = mapper.readValue(json, DomainEvent.class);
        assertThat(deserialized).isInstanceOf(RoundCompleted.class);
        assertThat(((RoundCompleted) deserialized).roundNumber()).isEqualTo(3);
    }

    @Test
    void matchResultFinalized_roundTrip() throws Exception {
        MatchResultFinalized event = new MatchResultFinalized("match-2", 3, 2);
        String json = mapper.writeValueAsString(event);
        DomainEvent deserialized = mapper.readValue(json, DomainEvent.class);
        assertThat(deserialized).isInstanceOf(MatchResultFinalized.class);
    }

    @Test
    void scheduleAdded_roundTrip() throws Exception {
        ScheduleAdded event = new ScheduleAdded("entry-1");
        String json = mapper.writeValueAsString(event);
        DomainEvent deserialized = mapper.readValue(json, DomainEvent.class);
        assertThat(deserialized).isInstanceOf(ScheduleAdded.class);
    }

    @Test
    void scheduleRemoved_roundTrip() throws Exception {
        ScheduleRemoved event = new ScheduleRemoved("entry-1");
        String json = mapper.writeValueAsString(event);
        DomainEvent deserialized = mapper.readValue(json, DomainEvent.class);
        assertThat(deserialized).isInstanceOf(ScheduleRemoved.class);
    }

    @Test
    void unknownDiscriminator_throwsInvalidTypeIdException() throws Exception {
        // AC2 — unknown discriminator → InvalidTypeIdException
        String json = "{\"type\":\"UNKNOWN_EVENT_TYPE\",\"someField\":\"value\"}";
        assertThatThrownBy(() -> mapper.readValue(json, DomainEvent.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void discriminatorTagAppearsInSerializedJson() throws Exception {
        ScoreUpdated event = new ScoreUpdated("m1", 1, 0);
        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"type\"");
        assertThat(json).contains("\"SCORE_UPDATED\"");
    }
}
