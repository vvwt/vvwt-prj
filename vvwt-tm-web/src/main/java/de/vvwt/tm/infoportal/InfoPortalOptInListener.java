// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

/**
 * Listener that processes {@link InfoPortalOptInEvent} after the originating transaction commits.
 *
 * <p>Implementations must execute the full registration chain (registerTenant → registerTournament
 * → postSnapshot) asynchronously.
 *
 * @since E62S02
 */
public interface InfoPortalOptInListener {

    /** Handle an opt-in event after commit. */
    void onOptIn(InfoPortalOptInEvent event);
}
