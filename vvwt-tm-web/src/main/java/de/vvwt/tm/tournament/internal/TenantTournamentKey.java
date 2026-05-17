// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import java.util.UUID;

/**
 * Composite key for the per-(tenant, tournament) FileChannel cache in {@link
 * DefaultAuditLogRepository} (E55S13).
 *
 * <p>Package-private value type — not part of the public tournament module API.
 *
 * @see DefaultAuditLogRepository
 */
record TenantTournamentKey(UUID tenantId, UUID tournamentId) {}
