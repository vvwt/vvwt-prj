package de.vvwt.info.dto.snapshot;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A complete point-in-time snapshot of a tournament's public state.
 *
 * <p>Snapshot responses are always delivered envelope-wrapped: {@code Envelope<TournamentSnapshot>}
 * (AC10 — no "MAY embed" ambiguity; unconditionally wrapped).
 *
 * <p>The {@link #scheduleEntries} list contains heterogeneous {@link ScheduleEntry} subtypes
 * (Match, SpecialAppointment, Pause) disambiguated by the Jackson discriminator on the sealed
 * interface (AC2).
 *
 * <p>The {@link #teams} list contains all registered teams with their stable UUIDs. The UUID is
 * used server-side for HMAC URL validation (E38S06 AC6); clients receive number + name only (AC12
 * scope restriction).
 *
 * <p>{@link #tournamentEnded} is {@code true} when the tournament has been superseded but is still
 * within the 24h grace window (E38S08 AC5 supersede UX). The SPA renders a read-only "Dieses
 * Turnier ist beendet" message and hides auto-update indicators when {@code true}.
 *
 * @param tournamentId stable tournament identifier (UUID)
 * @param tenantId stable tenant identifier (UUID)
 * @param sequenceNumber monotonically increasing version counter; clients use this for
 *     optimistic-concurrency checks and FULL_RESYNC detection
 * @param scheduleEntries ordered list of schedule entries for this tournament
 * @param teams registered teams (teamId UUID + name + number) — E38S06 AC6 HMAC iteration
 * @param tournamentEnded {@code true} when tournament superseded within 24h grace (E38S08 AC5); SPA
 *     shows frozen final-state view with DE message; {@code false} for active tournaments
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC6,
 *     AC12</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E38S08.story.md">E38S08 AC5</a>
 */
public record TournamentSnapshot(
        @JsonProperty("tournamentId") String tournamentId,
        @JsonProperty("tenantId") String tenantId,
        @JsonProperty("sequenceNumber") Long sequenceNumber,
        @JsonProperty("scheduleEntries") List<ScheduleEntry> scheduleEntries,
        @JsonProperty("teams") List<TeamEntry> teams,
        @JsonProperty("tournament_ended") boolean tournamentEnded) {}
