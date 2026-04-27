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
 * @param tournamentId stable tournament identifier (UUID)
 * @param tenantId stable tenant identifier (UUID)
 * @param sequenceNumber monotonically increasing version counter; clients use this for
 *     optimistic-concurrency checks and FULL_RESYNC detection
 * @param scheduleEntries ordered list of schedule entries for this tournament
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 */
public record TournamentSnapshot(
        @JsonProperty("tournamentId") String tournamentId,
        @JsonProperty("tenantId") String tenantId,
        @JsonProperty("sequenceNumber") Long sequenceNumber,
        @JsonProperty("scheduleEntries") List<ScheduleEntry> scheduleEntries) {}
