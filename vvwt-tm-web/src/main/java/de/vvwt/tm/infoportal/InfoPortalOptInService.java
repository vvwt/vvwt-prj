// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import java.util.UUID;

/**
 * Per-tournament opt-in control for Info-Portal publication.
 *
 * <p>When {@code info-portal.url} is not configured, {@link #getOptInStatus} returns {@code
 * "DISABLED"} and {@link #optIn} throws {@link IllegalStateException}.
 *
 * @since E62S02
 */
public interface InfoPortalOptInService {

    /**
     * Returns the current opt-in state for the given tournament.
     *
     * @return {@code "REGISTERED"}, {@code "ERROR"}, {@code "NOT_REGISTERED"}, or {@code
     *     "DISABLED"} when the feature is not configured
     */
    String getOptInStatus(UUID locationId, String tournamentId);

    /**
     * Opts a tournament into Info-Portal publication.
     *
     * <p>Validates preconditions synchronously then publishes {@link InfoPortalOptInEvent} for
     * async after-commit processing.
     *
     * @throws IllegalStateException if the feature is not configured, the tournament has no teams,
     *     or the tournament is already registered
     */
    void optIn(UUID locationId, UUID tournamentId);
}
