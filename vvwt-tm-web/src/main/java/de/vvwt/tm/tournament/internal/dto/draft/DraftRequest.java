// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto.draft;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * REST request body for draft preview and apply endpoints.
 *
 * <p>Inventory: E21S01 line 436. Reconstructed under {@code
 * de.vvwt.tm.tournament.internal.dto.draft} per DEC-21.
 *
 * @see DraftSectionRequest
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public record DraftRequest(@NotNull @Valid List<DraftSectionRequest> sections) {}
