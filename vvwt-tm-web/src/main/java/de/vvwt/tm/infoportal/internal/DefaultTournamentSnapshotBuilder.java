// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal.internal;

import de.vvwt.info.dto.snapshot.ScheduleEntry;
import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.tm.infoportal.TournamentSnapshotBuilder;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link TournamentSnapshotBuilder}.
 *
 * <p>Assembles a {@link TournamentSnapshot} from TM domain data by:
 *
 * <ol>
 *   <li>Loading teams → assembling {@link TeamEntry} list (AC2, AC5).
 *   <li>Loading tournament status → deriving {@code tournamentEnded} flag.
 *   <li>Loading phases, avatars, matches, and phase breaks → assembling {@link ScheduleEntry} list
 *       (AC3).
 *   <li>Returning a fully assembled {@link TournamentSnapshot} with no null fields (AC4).
 * </ol>
 *
 * <h2>ScheduleEntry scope</h2>
 *
 * <p>TM domain data yields {@link ScheduleEntry.Match} entries (one per {@link Match}) and {@link
 * ScheduleEntry.Pause} entries (one per {@link PhaseBreak}). The {@link
 * ScheduleEntry.SpecialAppointment} subtype is defined in {@code vvwt-info-dto} but has no
 * corresponding TM entity in this phase — no {@code SpecialAppointment} entries are produced by
 * this builder (delivery-time finding recorded in {@code impl-report.md}).
 *
 * <h2>tournamentEnded semantics</h2>
 *
 * <p>{@code tournamentEnded = true} when the tournament status is {@code COMPLETED} or {@code
 * CANCELLED} — the tournament is no longer accepting new results. {@code false} for all other
 * statuses (DRAFT, PLANNED, ACTIVE).
 *
 * <h2>Avatar → team name resolution</h2>
 *
 * <p>Each {@link Match} references two {@link TeamAvatar} rows by UUID. The builder resolves avatar
 * UUIDs → team UUIDs → {@link Team#getDescription()} using in-memory maps built per phase. If an
 * avatar is unassigned ({@code teamId == null}) or the team is not found, the name defaults to an
 * empty string (null-safe per AC4).
 *
 * <h2>roundNumber semantics</h2>
 *
 * <p>{@link Match#getLapNumber()} is the 1-based lap (round) number. Pre-slot-opt matches have
 * {@code lapNumber == null}; in that case {@code roundNumber} is emitted as {@code 0}.
 *
 * @see TournamentSnapshotBuilder
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E62S01.story.md">
 *     E62S01 AC1–AC9</a>
 * @since E62S01
 */
@Service
public class DefaultTournamentSnapshotBuilder implements TournamentSnapshotBuilder {

    private static final String DEFAULT_PAUSE_LABEL = "Pause";

    private final TournamentRepository tournamentRepository;
    private final TeamRepository teamRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final PhaseBreakRepository phaseBreakRepository;

    public DefaultTournamentSnapshotBuilder(
            TournamentRepository tournamentRepository,
            TeamRepository teamRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamAvatarRepository teamAvatarRepository,
            PhaseBreakRepository phaseBreakRepository) {
        this.tournamentRepository = tournamentRepository;
        this.teamRepository = teamRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.phaseBreakRepository = phaseBreakRepository;
    }

    @Override
    public TournamentSnapshot build(UUID tournamentId, String tenantId, long sequenceNumber) {
        // ── Step 1: Load tournament (for tournamentEnded flag) ────────────────
        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(
                                () ->
                                        new NoSuchElementException(
                                                "Tournament not found: " + tournamentId));

        boolean tournamentEnded = isTournamentEnded(tournament);

        // ── Step 2: Build TeamEntry list (AC2, AC5) ───────────────────────────
        List<Team> teams = teamRepository.findByTournamentId(tournamentId);
        List<TeamEntry> teamEntries = buildTeamEntries(teams);

        // ── Step 3: Build team lookup map (teamId → description) ─────────────
        Map<UUID, String> teamNameByTeamId = buildTeamNameMap(teams);

        // ── Step 4: Build scheduleEntries list (AC3) ──────────────────────────
        List<ScheduleEntry> scheduleEntries = buildScheduleEntries(tournamentId, teamNameByTeamId);

        // ── Step 5: Assemble snapshot ─────────────────────────────────────────
        return new TournamentSnapshot(
                tournamentId.toString(),
                tenantId,
                sequenceNumber,
                scheduleEntries,
                teamEntries,
                tournamentEnded);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the tournament is ended (COMPLETED or CANCELLED).
     *
     * <p>A tournament is considered ended for Info-Portal purposes when its status indicates no
     * further activity — either all phases completed or the tournament was cancelled by the
     * operator. DRAFT and PLANNED tournaments are not yet started; ACTIVE tournaments are in
     * progress.
     */
    private boolean isTournamentEnded(Tournament tournament) {
        String status = tournament.getStatus();
        return "COMPLETED".equals(status) || "CANCELLED".equals(status);
    }

    /**
     * Maps each {@link Team} to a {@link TeamEntry}.
     *
     * <p>{@code teamId = team.getId().toString()} (AC5 — UUID string form pinned for reader-token
     * validation). {@code name = team.getDescription()} (AC2 — human-readable label per DEC-42 D5).
     * {@code number = team.getTeamNumber()} (AC2).
     */
    private List<TeamEntry> buildTeamEntries(List<Team> teams) {
        List<TeamEntry> entries = new ArrayList<>(teams.size());
        for (Team team : teams) {
            entries.add(
                    new TeamEntry(
                            team.getId().toString(), team.getDescription(), team.getTeamNumber()));
        }
        return entries;
    }

    /** Builds a {@code teamId → team.description} map for avatar-to-team-name resolution. */
    private Map<UUID, String> buildTeamNameMap(List<Team> teams) {
        Map<UUID, String> map = new HashMap<>(teams.size() * 2);
        for (Team team : teams) {
            map.put(team.getId(), team.getDescription());
        }
        return map;
    }

    /**
     * Builds the flat {@link ScheduleEntry} list across all phases.
     *
     * <p>Per phase: loads {@link TeamAvatar} rows → builds {@code avatarId → teamName} map → maps
     * each {@link Match} to a {@link ScheduleEntry.Match} → maps each {@link PhaseBreak} to a
     * {@link ScheduleEntry.Pause}.
     *
     * <p>{@link ScheduleEntry.SpecialAppointment} is not produced — the TM data model has no
     * corresponding entity in the Phase-1 scope (delivery-time finding per E62S01 AC3 notes).
     */
    private List<ScheduleEntry> buildScheduleEntries(
            UUID tournamentId, Map<UUID, String> teamNameByTeamId) {

        List<Phase> phases = phaseRepository.findByTournamentId(tournamentId);
        List<ScheduleEntry> entries = new ArrayList<>();

        for (Phase phase : phases) {
            UUID phaseId = phase.getId();

            // Build avatarId → teamName map for this phase
            Map<UUID, String> teamNameByAvatarId = buildAvatarNameMap(phaseId, teamNameByTeamId);

            // Map matches → ScheduleEntry.Match
            List<Match> matches = matchRepository.findByPhaseId(phaseId);
            for (Match match : matches) {
                entries.add(toMatchEntry(match, teamNameByAvatarId));
            }

            // Map phase breaks → ScheduleEntry.Pause
            List<PhaseBreak> breaks = phaseBreakRepository.findByPhaseId(phaseId);
            for (PhaseBreak pb : breaks) {
                entries.add(toPauseEntry(pb));
            }
        }

        return entries;
    }

    /**
     * Builds a {@code avatarId → teamName} map for the given phase.
     *
     * <p>An avatar with {@code teamId == null} (unassigned slot) maps to an empty string. A {@code
     * teamId} not found in {@code teamNameByTeamId} also maps to an empty string (AC4 null-safety).
     */
    private Map<UUID, String> buildAvatarNameMap(UUID phaseId, Map<UUID, String> teamNameByTeamId) {

        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        Map<UUID, String> map = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar avatar : avatars) {
            String name = "";
            if (avatar.getTeamId() != null) {
                name = teamNameByTeamId.getOrDefault(avatar.getTeamId(), "");
            }
            map.put(avatar.getId(), name);
        }
        return map;
    }

    /**
     * Maps a TM {@link Match} to a {@link ScheduleEntry.Match}.
     *
     * <ul>
     *   <li>{@code id} = match UUID as string.
     *   <li>{@code homeTeamName} = avatar1 → team description (empty string if unresolvable).
     *   <li>{@code awayTeamName} = avatar2 → team description (empty string if unresolvable).
     *   <li>{@code roundNumber} = {@link Match#getLapNumber()} — {@code 0} if {@code null} (pre-
     *       slot-opt match).
     * </ul>
     */
    private ScheduleEntry.Match toMatchEntry(Match match, Map<UUID, String> teamNameByAvatarId) {

        String home = teamNameByAvatarId.getOrDefault(match.getMemberAvatar1Id(), "");
        String away = teamNameByAvatarId.getOrDefault(match.getMemberAvatar2Id(), "");
        int roundNumber = match.getLapNumber() != null ? match.getLapNumber() : 0;

        return new ScheduleEntry.Match(match.getId().toString(), home, away, roundNumber);
    }

    /**
     * Maps a TM {@link PhaseBreak} to a {@link ScheduleEntry.Pause}.
     *
     * <ul>
     *   <li>{@code id} = phase break UUID as string.
     *   <li>{@code label} = {@link PhaseBreak#getLabel()} if non-null, otherwise {@value
     *       #DEFAULT_PAUSE_LABEL}.
     * </ul>
     */
    private ScheduleEntry.Pause toPauseEntry(PhaseBreak pb) {
        String label = pb.getLabel() != null ? pb.getLabel() : DEFAULT_PAUSE_LABEL;
        return new ScheduleEntry.Pause(pb.getId().toString(), label);
    }
}
