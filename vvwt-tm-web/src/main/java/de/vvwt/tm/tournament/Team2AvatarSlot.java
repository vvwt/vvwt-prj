// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

/**
 * Immutable coordinate pair representing one team-to-avatar slot assignment produced by a {@link
 * Team2AvatarDistributor}.
 *
 * <p>Carries only structural identity: the group number and the position within that group. No
 * {@code teamId} field — teamId population is the sole responsibility of {@link
 * de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService#commitTransition} (DEC-9, DEC-59
 * Clause C, AC10).
 *
 * @param groupNumber the 1-based group index within the phase
 * @param groupPosition the 1-based position within the group
 * @see Team2AvatarDistributor
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity (phaseId, groupNumber,
 *     groupPosition)</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributor strategy interface</a>
 * @see <a href="E58S02">E58S02 — AC1</a>
 */
public record Team2AvatarSlot(int groupNumber, int groupPosition) {}
