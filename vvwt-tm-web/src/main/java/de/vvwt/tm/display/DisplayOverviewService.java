// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.display;

import de.vvwt.tm.tournament.exceptions.UnauthorizedException;

/**
 * Port for the display device overview service (DEC-35, E25S01).
 *
 * <p>Implemented by {@link de.vvwt.tm.display.internal.DefaultDisplayOverviewService}. Consumed by
 * display-facing REST controllers in the {@code web} module (E25S02 scope).
 *
 * <p>Authored Q-1a TDD RED-first at canonical FQN {@code de.vvwt.tm.display.DisplayOverviewService}
 * per DEC-22 Iron Law + DEC-35 interface-in-root convention + D-7 Coexistence Option γ.
 *
 * <p>Return types are bounded-context-owned query-shape records at {@code de.vvwt.tm.display.*} per
 * DEC-40 §2026-04-27 Clarification (Pattern A). This avoids a {@code display→web} dependency that
 * would create a {@code display↔web} Modulith cycle (escalation commit {@code 23d947b}).
 *
 * <p>Three method signatures preserved verbatim per C-3 signature-preservation.
 *
 * @see de.vvwt.tm.display.internal.DefaultDisplayOverviewService
 * @see DEC-35
 * @see DEC-22
 * @see DEC-40
 * @see E25S01
 */
public interface DisplayOverviewService {

    /**
     * Returns the current phase overview for the display device's tenant.
     *
     * @param deviceToken the display device's token
     * @return phase overview response (never null)
     * @throws UnauthorizedException if the token is invalid or not a DISPLAY device
     * @throws NoActivePhaseException if no active phase exists
     */
    DisplayPhaseOverviewResponse getPhaseOverview(String deviceToken);

    /**
     * Returns all matches for the given lap (or current lap if omitted).
     *
     * @param deviceToken the display device's token
     * @param lap the lap number to query; {@code null} = current lap
     * @return matches response (never null)
     * @throws UnauthorizedException if the token is invalid or not a DISPLAY device
     * @throws NoActivePhaseException if no active phase exists
     */
    DisplayMatchesResponse getMatchesByLap(String deviceToken, Integer lap);

    /**
     * Returns group standings for the current active phase, sorted per D-33.
     *
     * @param deviceToken the display device's token
     * @return group standings response (never null)
     * @throws UnauthorizedException if the token is invalid or not a DISPLAY device
     * @throws NoActivePhaseException if no active phase exists
     */
    DisplayGroupStandingsResponse getGroupStandings(String deviceToken);
}
