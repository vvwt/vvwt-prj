// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer;

import java.util.UUID;

/**
 * Thrown when a tournament has no operator-configured schedule (DraftConfig) or the configured
 * schedule is invalid, making it impossible to compute a timer timeline.
 *
 * <p>Maps to HTTP 404 with error code {@code "NO_SCHEDULE_CONFIGURED"}. The timer UI displays a
 * dedicated "no schedule" error state when this code is received.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.NoScheduleConfiguredException} per DEC-21 module
 * layout. Caught cross-module by {@code web.GlobalExceptionHandler.handleNoScheduleConfigured}
 * (E11S10).
 *
 * @see NoActiveTournamentException
 * @see <a href="contexts/artefacts/stories/E11S10.story.md">Story E11S10</a>
 */
public class NoScheduleConfiguredException extends RuntimeException {

    private final String errorCode;

    /**
     * Creates the exception when DraftConfig is absent or unparseable.
     *
     * @param tournamentId the tournament UUID
     * @param reason a developer-readable reason (not exposed to clients)
     */
    public NoScheduleConfiguredException(UUID tournamentId, String reason) {
        super("Tournament " + tournamentId + " has no usable schedule configuration: " + reason);
        this.errorCode = "NO_SCHEDULE_CONFIGURED";
    }

    /**
     * Creates the exception when DraftConfig is absent or unparseable (convenience constructor).
     *
     * @param tournamentId the tournament UUID
     */
    public NoScheduleConfiguredException(UUID tournamentId) {
        this(tournamentId, "draftJson absent, invalid, or inconsistent with phase count");
    }

    /**
     * Returns the machine-readable error code for the timer UI to display a specific message.
     *
     * @return always {@code "NO_SCHEDULE_CONFIGURED"}
     */
    public String getErrorCode() {
        return errorCode;
    }
}
