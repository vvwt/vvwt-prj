// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.internal.dto;

/**
 * Request body DTO for {@code PUT /api/phases/{phaseId}/transition-settings} (E66S02).
 *
 * <p>Carries the new {@code sortType} and {@code distributionMode} registry keys for the target
 * PREPARED phase's {@link de.vvwt.tm.tournament.draft.DraftSection}.
 *
 * <p>Lives in {@code web.internal.dto} per DEC-40 Clause A (primary-adapter isolation). Mapped to
 * the {@link de.vvwt.tm.tournament.PhaseTransitionService#updateSortAndDistribution} call inside
 * the controller — not passed to the service directly — to prevent a forbidden {@code
 * tournament→web} import (DEC-40 Clause B).
 *
 * @param sortType the new sort-type registry key (e.g. {@code "team_number"}, {@code
 *     "placement_group"}, {@code "group_placement"})
 * @param distributionMode the new distribution-mode registry key (e.g. {@code "sequential"}, {@code
 *     "round_robin"})
 * @see de.vvwt.tm.web.PhaseTransitionController
 * @see de.vvwt.tm.tournament.PhaseTransitionService#updateSortAndDistribution
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E66S02">E66S02 — AC4, AC5, AC6</a>
 */
public record TransitionSettingsRequest(String sortType, String distributionMode) {}
