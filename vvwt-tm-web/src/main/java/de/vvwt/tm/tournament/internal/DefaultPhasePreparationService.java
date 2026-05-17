// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhasePreparationService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring service that orchestrates Phase preparation: match generation + referee assignment (E21S08
 * reconstruction — CRITICAL-PATH-CONVERGENCE per Brief O-8).
 *
 * <p>Reconstruction-in-place counterpart of {@code de.vvwt.tm.domain.PhasePreparationService}
 * (inventory row 180). Lives at {@code de.vvwt.tm.tournament.internal} per AC-PACKAGE-D8. Uses new
 * {@code de.vvwt.tm.tournament.*} types.
 *
 * <h2>Brief O-8 — Critical-path convergence (AC-ORCHESTRATION-CONVERGENCE)</h2>
 *
 * <p>This service is the single convergence gate of the E21 dependency graph. Its four
 * collaborators come from four upstream stories:
 *
 * <ul>
 *   <li>{@link MatchGeneratorRegistry} — E21S08 (this story, new reconstruction)
 *   <li>{@link Phase} aggregate ({@link PhaseRepository}) — E21S03
 *   <li>{@link Match} aggregate persistence ({@link MatchRepository}) — E21S04/E21S05
 *   <li>Round / referee-pool collaborators ({@link TeamAvatarRepository}, {@link RefereeAssigner})
 *       — E21S04/E21S05/E21S08 (this story)
 * </ul>
 *
 * <h2>Orchestration flow</h2>
 *
 * <ol>
 *   <li>Load phase; validate phase exists.
 *   <li>Delete existing matches for the phase (idempotency).
 *   <li>Load avatars for the phase.
 *   <li>Resolve generator from {@link MatchGeneratorRegistry} by key.
 *   <li>Generate matches.
 *   <li>Persist each generated match.
 *   <li>Invoke {@link RefereeAssigner#assignReferees(UUID)}.
 *   <li>Return the assignment report.
 * </ol>
 *
 * <p>Note: Slot optimization ({@code optimizeSlots}) is NOT included here — it remains in the
 * legacy {@code de.vvwt.tm.domain.PhasePreparationService} for now. This E21S08 reconstruction
 * covers only match generation + referee assignment, which is the testable scope of the new
 * boundary-API types.
 *
 * <p>Legacy {@code de.vvwt.tm.domain.PhasePreparationService} remains untouched until E21S13 atomic
 * cutover per DEC-32.
 *
 * @see MatchGeneratorRegistry
 * @see MatchGenerator
 * @see RefereeAssigner
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, internal package</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (reconstruction-in-place)</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 * @see <a href="E21S08">E21S08 — inventory row 180</a>
 */
@Service("tmPhasePreparationService")
public class DefaultPhasePreparationService implements PhasePreparationService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultPhasePreparationService.class);

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final MatchGeneratorRegistry matchGeneratorRegistry;

    /**
     * Constructs the service.
     *
     * <p>Brief O-8 convergence map — collaborators by upstream story:
     *
     * <ul>
     *   <li>{@code phaseRepository} — E21S03 (Phase aggregate)
     *   <li>{@code matchRepository} — E21S04/E21S05 (Match aggregate + SetResult)
     *   <li>{@code teamAvatarRepository} — E21S04 (TeamAvatar aggregate)
     *   <li>{@code matchGeneratorRegistry} — E21S08 (this story, MatchGenerator SPI)
     * </ul>
     *
     * <p>E51S06: {@code refereeAssigner} removed from this constructor — referee assignment is now
     * part of {@code commitTransition} in {@link
     * de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService} (DEC-55 D-10). {@link
     * #generateMatches} remains for the E51S03 match-gen background job.
     */
    public DefaultPhasePreparationService(
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamAvatarRepository teamAvatarRepository,
            MatchGeneratorRegistry matchGeneratorRegistry) {
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.matchGeneratorRegistry = matchGeneratorRegistry;
    }

    /**
     * Generates matches for a phase WITHOUT running referee assignment.
     *
     * <p>Called from {@link de.vvwt.tm.tournament.internal.DefaultDraftService#apply} immediately
     * after phase creation. At this point slot optimization has not yet been run, so referee
     * assignment (which requires lap + field numbers) cannot execute. Slot optimization and referee
     * assignment are triggered separately by the operator after draft apply.
     *
     * <p>Idempotent: existing matches are deleted before new ones are generated.
     *
     * @param phaseId the phase UUID; must not be {@code null}
     * @param generatorKey the match generator bean id; must not be {@code null}
     * @throws IllegalArgumentException if either argument is null or the phase does not exist
     */
    @Override
    @Transactional
    public void generateMatches(UUID phaseId, String generatorKey) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }
        if (generatorKey == null) {
            throw new IllegalArgumentException("generatorKey must not be null");
        }

        Phase phase =
                phaseRepository
                        .findById(phaseId)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Phase not found: " + phaseId));

        List<Match> existing = matchRepository.findByPhaseId(phaseId);
        if (!existing.isEmpty()) {
            matchRepository.deleteByPhaseId(phaseId);
            LOG.info(
                    "generateMatches: phase={} — deleted {} existing matches before re-generation",
                    phaseId,
                    existing.size());
        }

        List<TeamAvatar> avatars =
                teamAvatarRepository.findByTournamentIdAndPhaseId(phase.getTournamentId(), phaseId);

        MatchGenerator generator = matchGeneratorRegistry.get(generatorKey);
        List<Match> generatedMatches = generator.generate(phase, avatars);

        for (Match match : generatedMatches) {
            matchRepository.save(match);
        }

        LOG.info(
                "generateMatches: phase={}, generator={}, avatars={}, generated {} matches",
                phaseId,
                generatorKey,
                avatars.size(),
                generatedMatches.size());
    }
}
