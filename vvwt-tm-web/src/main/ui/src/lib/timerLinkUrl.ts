// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
export function buildTimerUrl(origin: string, tournamentId: string): string {
    if (!tournamentId) return '';
    return `${origin}/timer/tournaments/${tournamentId}`;
}
