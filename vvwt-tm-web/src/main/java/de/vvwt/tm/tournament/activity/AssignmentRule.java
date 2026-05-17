// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.activity;

/**
 * Enumeration of supported activity assignment rules (E08S02, AC3).
 *
 * <p>The {@code assignment_rule} column in the {@code activity_types} table stores the {@linkplain
 * #name() enum name} as a VARCHAR. Adding a new rule post-V1 requires:
 *
 * <ol>
 *   <li>Adding a new constant to this enum.
 *   <li>Implementing the rule logic in the assignment service (E08S04).
 *   <li>No schema migration — the VARCHAR column is rule-name-agnostic.
 * </ol>
 *
 * <h2>V1 rule</h2>
 *
 * <ul>
 *   <li>{@link #FIRST_FREE_ROUND} — assign the team to the activity during its first free
 *       (non-playing) round in the phase.
 * </ul>
 *
 * <p><b>E45S01 relocation note:</b> Relocated from {@code de.vvwt.tm.domain.AssignmentRule} into
 * the {@code tournament.activity} sub-package per DEC-21 atomic-cutover + DEC-35.
 *
 * @see ActivityType
 */
public enum AssignmentRule {

    /**
     * Assign the team to the activity during its first free round in the phase.
     *
     * <p>This is the V1 rule and the primary rule for the Team Photo use case (Mannschaftsfoto).
     * When a capacity limit is set ({@link ActivityType#getCapacityPerRound()} &gt; 0), the
     * assignment service (E08S04) respects that limit and may defer to a later free round.
     */
    FIRST_FREE_ROUND
}
