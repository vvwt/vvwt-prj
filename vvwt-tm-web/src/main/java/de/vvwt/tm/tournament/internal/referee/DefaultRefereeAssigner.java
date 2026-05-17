// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.referee;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.RefereeAssigner;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link RefereeAssigner}.
 *
 * <p>Spring service that assigns referee teams to every eligible match in a phase (E21S08
 * reconstruction). Reconstruction-in-place counterpart of {@code
 * de.vvwt.tm.domain.referee.RefereeAssigner} (inventory row 276). Lives at {@code
 * de.vvwt.tm.tournament.internal.referee} per AC-PACKAGE-D8. Uses new {@code
 * de.vvwt.tm.tournament.*} types instead of {@code de.vvwt.tm.domain.*} types.
 *
 * <h2>Core algorithm</h2>
 *
 * <p>For every lap in the phase the service:
 *
 * <ol>
 *   <li>Determines which teams are playing in that lap.
 *   <li>Builds a candidate set: teams with {@code referee_assignment = TRUE} that are NOT playing.
 *   <li>Applies preference ordering from {@code Match.refereePreferenceConfig}.
 *   <li>Applies round-robin balancing (fewest prior assignments goes first).
 *   <li>Assigns the first candidate to the match; records warning if none available.
 * </ol>
 *
 * <p>Matches with a non-null {@code refereeDescription} are left unchanged (manual override).
 *
 * <p>Legacy {@code de.vvwt.tm.domain.referee.RefereeAssigner} remains untouched until E21S13 atomic
 * cutover per DEC-32.
 *
 * @see RefereeAssignmentReport
 * @see RefereePreferenceConfig
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from RefereeAssigner, implements
 *     {@link RefereeAssigner})
 */
@Service("tmRefereeAssigner")
public class DefaultRefereeAssigner implements RefereeAssigner {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRefereeAssigner.class);

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamRepository teamRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the service. Spring injects all collaborators.
     *
     * @param phaseRepository repository for {@link Phase} entities
     * @param matchRepository repository for {@link Match} entities
     * @param teamRepository repository for {@link Team} entities
     * @param teamAvatarRepository repository for {@link TeamAvatar} entities
     * @param objectMapper Jackson mapper for parsing {@link RefereePreferenceConfig}
     */
    public DefaultRefereeAssigner(
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamRepository teamRepository,
            TeamAvatarRepository teamAvatarRepository,
            ObjectMapper objectMapper) {
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamRepository = teamRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public RefereeAssignmentReport assignReferees(UUID phaseId) {
        if (phaseId == null) {
            throw new NullPointerException("phaseId must not be null");
        }

        Phase phase =
                phaseRepository
                        .findById(phaseId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Phase not found: "
                                                        + phaseId
                                                        + ". No assignment performed."));

        List<Match> allMatches = matchRepository.findByPhaseId(phaseId);

        if (allMatches.isEmpty()) {
            LOG.info(
                    "DefaultRefereeAssigner: phase {} has no matches — nothing to assign.",
                    phaseId);
            return RefereeAssignmentReport.builder().totalMatches(0).build();
        }

        for (Match match : allMatches) {
            if (match.getLapNumber() == null || match.getFieldNumber() == null) {
                throw new IllegalStateException(
                        "Phase "
                                + phaseId
                                + " has matches without slot coordinates"
                                + " (match "
                                + match.getId()
                                + "). Run slot optimization before calling assignReferees.");
            }
        }

        List<TeamAvatar> avatars =
                teamAvatarRepository.findByTournamentIdAndPhaseId(phase.getTournamentId(), phaseId);
        Map<UUID, UUID> avatarIdToTeamId = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar avatar : avatars) {
            avatarIdToTeamId.put(avatar.getId(), avatar.getTeamId());
        }

        List<Team> teams = teamRepository.findByTournamentId(phase.getTournamentId());
        Map<UUID, Team> teamById = new HashMap<>(teams.size() * 2);
        Set<UUID> eligibleRefereeTeamIds = new HashSet<>();
        for (Team team : teams) {
            teamById.put(team.getId(), team);
            if (team.isRefereeAssignment()) {
                eligibleRefereeTeamIds.add(team.getId());
            }
        }

        Map<Integer, List<Match>> matchesByLap = new TreeMap<>();
        for (Match match : allMatches) {
            matchesByLap.computeIfAbsent(match.getLapNumber(), k -> new ArrayList<>()).add(match);
        }

        RefereeAssignmentReport.Builder reportBuilder =
                RefereeAssignmentReport.builder().totalMatches(allMatches.size());

        for (Map.Entry<Integer, List<Match>> lapEntry : matchesByLap.entrySet()) {
            int lapNumber = lapEntry.getKey();
            List<Match> lapMatches = lapEntry.getValue();

            Set<UUID> playingTeamIds = new HashSet<>();
            for (Match match : lapMatches) {
                UUID team1Id = avatarIdToTeamId.get(match.getMemberAvatar1Id());
                UUID team2Id = avatarIdToTeamId.get(match.getMemberAvatar2Id());
                if (team1Id != null) playingTeamIds.add(team1Id);
                if (team2Id != null) playingTeamIds.add(team2Id);
            }

            List<Match> toAssign = new ArrayList<>();
            int lapOverrideCount = 0;
            for (Match match : lapMatches) {
                if (match.getRefereeDescription() != null) {
                    lapOverrideCount++;
                    LOG.debug(
                            "DefaultRefereeAssigner: lap={} match={} — manual override, skipping.",
                            lapNumber,
                            match.getId());
                } else {
                    toAssign.add(match);
                }
            }
            reportBuilder.addOverridden(lapOverrideCount);

            toAssign.sort(Comparator.comparing(Match::getId));

            Set<UUID> alreadyRefereesThisLap = new HashSet<>();

            for (Match match : toAssign) {
                RefereePreferenceConfig prefs =
                        RefereePreferenceConfig.parse(
                                match.getRefereePreferenceConfig(), objectMapper);

                Set<UUID> candidateSet = new HashSet<>(eligibleRefereeTeamIds);
                candidateSet.removeAll(playingTeamIds);
                candidateSet.removeAll(alreadyRefereesThisLap);

                List<UUID> candidateList = buildCandidateList(candidateSet, prefs, reportBuilder);

                if (candidateList.isEmpty()) {
                    String warning =
                            "Lap "
                                    + lapNumber
                                    + ": no eligible referee available for match "
                                    + match.getId()
                                    + ".";
                    LOG.warn("DefaultRefereeAssigner: {}", warning);
                    reportBuilder.addWarning(warning).incrementNoReferee();
                    continue;
                }

                UUID chosenTeamId = candidateList.get(0);
                match.setRefereeTeamId(chosenTeamId);
                matchRepository.save(match);
                reportBuilder.incrementAssigned().recordAssignment(chosenTeamId);
                alreadyRefereesThisLap.add(chosenTeamId);

                LOG.debug(
                        "DefaultRefereeAssigner: lap={} field={} match={} — assigned"
                                + " refereeTeamId={}.",
                        lapNumber,
                        match.getFieldNumber(),
                        match.getId(),
                        chosenTeamId);
            }
        }

        RefereeAssignmentReport report = reportBuilder.build();

        LOG.info(
                "DefaultRefereeAssigner: phase={} — total={}, assigned={}, overridden={},"
                        + " noReferee={}, warnings={}",
                phaseId,
                report.getTotalMatches(),
                report.getAssignedCount(),
                report.getOverriddenCount(),
                report.getNoRefereeCount(),
                report.getWarnings().size());

        return report;
    }

    private List<UUID> buildCandidateList(
            Set<UUID> candidateSet,
            RefereePreferenceConfig prefs,
            RefereeAssignmentReport.Builder reportBuilder) {
        if (candidateSet.isEmpty()) {
            return List.of();
        }

        List<UUID> result = new ArrayList<>(candidateSet.size());
        Set<UUID> remaining = new HashSet<>(candidateSet);

        for (UUID preferredId : prefs.getPreferred()) {
            if (remaining.remove(preferredId)) {
                result.add(preferredId);
            }
        }

        List<UUID> remainingList = new ArrayList<>(remaining);
        remainingList.sort(
                Comparator.comparingInt((UUID teamId) -> reportBuilder.getAssignmentCount(teamId))
                        .thenComparing(Comparator.naturalOrder()));
        result.addAll(remainingList);

        return result;
    }
}
