/**
 * Pure utility functions for PhaseTransition drag-and-drop logic (E48S08).
 *
 * Extracted to a plain TypeScript module so that tests can invoke functions directly
 * without JSDOM DnD limitations (AC-FRONTEND-VITEST-DRAGDROP-RED).
 *
 * DEC-22 Iron Law: functions tested RED-first via PhaseTransition.test.ts.
 */

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
    const srcTeam = updated[srcIdx].teamId;
    updated[srcIdx].teamId = updated[tgtIdx].teamId;
    updated[tgtIdx].teamId = srcTeam;
    return updated;
}
