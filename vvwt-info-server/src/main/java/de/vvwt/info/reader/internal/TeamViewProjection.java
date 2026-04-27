package de.vvwt.info.reader.internal;

import de.vvwt.info.dto.snapshot.ScheduleEntry;
import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Filters a full {@link TournamentSnapshot} to the per-team view scope (E38S06 AC12).
 *
 * <p>Per-team view projection rules (Brief S-1 + D-G6 + D-G6a):
 *
 * <ul>
 *   <li>{@link ScheduleEntry.Match}: included iff the requesting team's name appears as {@code
 *       homeTeamName} or {@code awayTeamName}.
 *   <li>{@link ScheduleEntry.SpecialAppointment}: always included (Sondertermine — all teams see
 *       all special appointments).
 *   <li>{@link ScheduleEntry.Pause}: always included (global schedule metadata; not team-specific).
 * </ul>
 *
 * <p>The team list ({@link TournamentSnapshot#teams()}) is preserved unchanged — it contains number
 * + name only for all teams, per AC12 ("team list (number + name only)"). The {@code teamId} UUID
 * in the team list is present in the state for HMAC computation but is included in the projected
 * snapshot; E38S08 client SHOULD ignore it (it is not the bearer token itself and does not grant
 * access on its own).
 *
 * <p>Phase/round structure ({@link TournamentSnapshot#sequenceNumber()}, tournament metadata) is
 * included as-is — these are metadata fields. The filtering is applied only to {@code
 * scheduleEntries}.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC12</a>
 */
@Component
public class TeamViewProjection {

    /**
     * Returns a filtered copy of {@code snapshot} containing only the entries visible to the
     * requesting team.
     *
     * @param snapshot the full tournament snapshot from {@code tournament.state}
     * @param requestingTeamEntry the resolved {@link TeamEntry} for the requesting team (from HMAC
     *     validation)
     * @return a new {@link TournamentSnapshot} with schedule entries filtered to team scope
     */
    public TournamentSnapshot project(TournamentSnapshot snapshot, TeamEntry requestingTeamEntry) {
        String teamName = requestingTeamEntry.name();

        List<ScheduleEntry> filtered =
                snapshot.scheduleEntries().stream()
                        .filter(entry -> isVisibleToTeam(entry, teamName))
                        .toList();

        return new TournamentSnapshot(
                snapshot.tournamentId(),
                snapshot.tenantId(),
                snapshot.sequenceNumber(),
                filtered,
                snapshot.teams(),
                snapshot.tournamentEnded());
    }

    /**
     * Determines whether the given schedule entry is visible to the requesting team.
     *
     * <p>Rules (AC12):
     *
     * <ul>
     *   <li>Match: visible iff team is home or away participant.
     *   <li>SpecialAppointment: always visible (Sondertermin).
     *   <li>Pause: always visible (global schedule event).
     * </ul>
     */
    boolean isVisibleToTeam(ScheduleEntry entry, String teamName) {
        return switch (entry) {
            case ScheduleEntry.Match m ->
                    teamName.equals(m.homeTeamName()) || teamName.equals(m.awayTeamName());
            case ScheduleEntry.SpecialAppointment ignored -> true;
            case ScheduleEntry.Pause ignored -> true;
        };
    }
}
