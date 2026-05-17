// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed schedule-entry hierarchy for tournament snapshots (AC2).
 *
 * <p>Phase 1 closed subtypes:
 *
 * <ul>
 *   <li>{@link Match} — a scheduled match between two teams
 *   <li>{@link SpecialAppointment} — a named event in the schedule (awards, opening, etc.)
 *   <li>{@link Pause} — a named break (lunch, half-time, etc.)
 * </ul>
 *
 * <p>Jackson polymorphic discrimination via {@code "type"} discriminator field. Unknown
 * discriminator values produce {@link com.fasterxml.jackson.databind.exc.InvalidTypeIdException}
 * (NOT silent fallback — AC2).
 *
 * <p>Consumers can exhaustively {@code switch} over all subtypes without a default branch.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ScheduleEntry.Match.class, name = "MATCH"),
    @JsonSubTypes.Type(
            value = ScheduleEntry.SpecialAppointment.class,
            name = "SPECIAL_APPOINTMENT"),
    @JsonSubTypes.Type(value = ScheduleEntry.Pause.class, name = "PAUSE")
})
public sealed interface ScheduleEntry
        permits ScheduleEntry.Match, ScheduleEntry.SpecialAppointment, ScheduleEntry.Pause {

    /**
     * A scheduled match between two teams.
     *
     * @param id unique entry identifier within the tournament
     * @param homeTeamName home team display name
     * @param awayTeamName away team display name
     * @param roundNumber the round in which this match takes place
     */
    record Match(String id, String homeTeamName, String awayTeamName, int roundNumber)
            implements ScheduleEntry {}

    /**
     * A named special appointment in the schedule (awards ceremony, opening, etc.).
     *
     * @param id unique entry identifier
     * @param title display title for this appointment
     */
    record SpecialAppointment(String id, String title) implements ScheduleEntry {}

    /**
     * A named break in the schedule (lunch, half-time, etc.).
     *
     * @param id unique entry identifier
     * @param label display label for this pause
     */
    record Pause(String id, String label) implements ScheduleEntry {}
}
