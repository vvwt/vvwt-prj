// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.reader;

import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;

/**
 * Projects a full {@link TournamentSnapshot} to the per-team view scope (E38S06 AC12).
 *
 * @see de.vvwt.info.reader.internal.DefaultTeamViewProjection
 * @see <a href="../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC12</a>
 */
public interface TeamViewProjection {

    /**
     * Returns a filtered copy of {@code snapshot} containing only the entries visible to the
     * requesting team.
     *
     * @param snapshot the full tournament snapshot
     * @param requestingTeamEntry the resolved {@link TeamEntry} for the requesting team
     * @return a new {@link TournamentSnapshot} with schedule entries filtered to team scope
     */
    TournamentSnapshot project(TournamentSnapshot snapshot, TeamEntry requestingTeamEntry);
}
