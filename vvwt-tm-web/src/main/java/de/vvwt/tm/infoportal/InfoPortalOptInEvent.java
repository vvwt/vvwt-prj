// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import java.util.UUID;

/**
 * Spring application event fired when a tournament opts in to Info-Portal publication.
 *
 * <p>Processed asynchronously after the current transaction commits by {@link
 * InfoPortalOptInListener}.
 *
 * @since E62S02
 */
public record InfoPortalOptInEvent(UUID locationId, UUID tournamentId, String tenantId) {}
