// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import type { TeamAvatarSlot } from '../stores/phaseTransitionStore.js';

/**
 * Swap the teamIds of two slots by index, returning a new array.
 *
 * The swap exchanges which team occupies which grid cell: each slot's
 * (groupNumber, groupPosition) stays fixed (it is the structural identity of the cell);
 * only the teamId values are exchanged between the two cells.
 *
 * This is a pure function — the input array is NOT mutated.
 *
 * @param slots  current slot array
 * @param srcIdx index of the dragged slot (drag source)
 * @param tgtIdx index of the drop target slot
 * @returns new array with teamIds of srcIdx and tgtIdx exchanged
 */
export function swapSlots(
    slots: TeamAvatarSlot[],
    srcIdx: number,
    tgtIdx: number
): TeamAvatarSlot[] {
    const updated = slots.map(s => ({ ...s }));
    // Swap all team-bound fields (teamId + E48S20 display fields).
    // Structural identity (groupNumber, groupPosition) stays fixed — it belongs to the cell.
    const {
        teamId: srcTeamId,
        teamNumber: srcTeamNumber,
        teamDescription: srcTeamDescription,
        sourceGroupNumber: srcSourceGroupNumber,
        sourceGroupPosition: srcSourceGroupPosition,
    } = updated[srcIdx];
    updated[srcIdx].teamId = updated[tgtIdx].teamId;
    updated[srcIdx].teamNumber = updated[tgtIdx].teamNumber;
    updated[srcIdx].teamDescription = updated[tgtIdx].teamDescription;
    updated[srcIdx].sourceGroupNumber = updated[tgtIdx].sourceGroupNumber;
    updated[srcIdx].sourceGroupPosition = updated[tgtIdx].sourceGroupPosition;
    updated[tgtIdx].teamId = srcTeamId;
    updated[tgtIdx].teamNumber = srcTeamNumber;
    updated[tgtIdx].teamDescription = srcTeamDescription;
    updated[tgtIdx].sourceGroupNumber = srcSourceGroupNumber;
    updated[tgtIdx].sourceGroupPosition = srcSourceGroupPosition;
    return updated;
}
