// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.scoring.ScoringRuleRegistry;
import de.vvwt.tm.scoring.SetValidationRule;
import de.vvwt.tm.scoring.SetValidationRuleRegistry;
import de.vvwt.tm.scoring.TournamentRuleResolver;
import de.vvwt.tm.tournament.Tournament;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link TournamentRuleResolver}.
 *
 * <p>Internal service that resolves both rule beans ({@link ScoringRule} + {@link
 * SetValidationRule}) for a given {@link Tournament} in a single operation.
 *
 * <p>Each {@code Tournament} row stores Spring bean IDs for each pluggable rule dimension. This
 * resolver translates those IDs into live rule instances by delegating to the appropriate
 * registries.
 *
 * <p>Consumer graph: {@code DefaultScoringService} (within {@code scoring.internal}); post-E22S06
 * also {@code DefaultScoreEntryService} (within {@code scoring.internal}). No cross-module
 * consumers.
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.TournamentRuleResolver} per DEC-22
 * Reconstruction-in-Place (E22S05). The new resolver takes only the two scoring registries (not the
 * legacy three-arg form that also accepted {@code MatchGeneratorRegistry} — that concern does not
 * belong to this module). Legacy type remains in place during the coexistence window ending at the
 * E22S11 cutover.
 *
 * @see ScoringRuleRegistry
 * @see SetValidationRuleRegistry
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from TournamentRuleResolver,
 *     implements {@link TournamentRuleResolver})
 */
@Component("scoringTournamentRuleResolver")
class DefaultTournamentRuleResolver implements TournamentRuleResolver {

    private final ScoringRuleRegistry scoringRuleRegistry;
    private final SetValidationRuleRegistry setValidationRuleRegistry;

    /**
     * Constructs the resolver. Spring injects both registries.
     *
     * @param scoringRuleRegistry registry of all {@link ScoringRule} beans; must not be {@code
     *     null}
     * @param setValidationRuleRegistry registry of all {@link SetValidationRule} beans; must not be
     *     {@code null}
     * @throws IllegalArgumentException if either registry is {@code null}
     */
    DefaultTournamentRuleResolver(
            ScoringRuleRegistry scoringRuleRegistry,
            SetValidationRuleRegistry setValidationRuleRegistry) {
        if (scoringRuleRegistry == null) {
            throw new IllegalArgumentException("scoringRuleRegistry must not be null");
        }
        if (setValidationRuleRegistry == null) {
            throw new IllegalArgumentException("setValidationRuleRegistry must not be null");
        }
        this.scoringRuleRegistry = scoringRuleRegistry;
        this.setValidationRuleRegistry = setValidationRuleRegistry;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@link Tournament#getScoringRuleId()} and {@link
     * Tournament#getSetValidationRuleId()}, then delegates to the respective registries. Both
     * registries throw {@code ValidationException} on an unknown ID — the security-relevant reject
     * at cascade entry (AC-RESOLVER-CASCADE-ENTRY, AC-SECURITY-ID-VALIDATION).
     */
    @Override
    public ResolvedRules resolve(Tournament tournament) {
        if (tournament == null) {
            throw new IllegalArgumentException("tournament must not be null");
        }
        ScoringRule scoringRule = scoringRuleRegistry.get(tournament.getScoringRuleId());
        SetValidationRule setValidationRule =
                setValidationRuleRegistry.get(tournament.getSetValidationRuleId());
        return new ResolvedRules(scoringRule, setValidationRule);
    }
}
