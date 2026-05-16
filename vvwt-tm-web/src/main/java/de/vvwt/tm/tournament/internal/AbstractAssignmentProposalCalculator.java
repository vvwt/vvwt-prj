package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamSortCalculator;
import java.util.Map;
import java.util.UUID;

/**
 * Abstract base for {@link TeamSortCalculator} implementations.
 *
 * <p>Provides shared proposal-calculation helpers to the three concrete sort-mode implementations:
 *
 * <ul>
 *   <li>{@link #requireTeamForDisplay(TeamAvatar, Map)} — resolves the {@link Team} aggregate for a
 *       given avatar (throws {@link IllegalStateException} on missing data).
 *   <li>{@link #buildProposal(TeamAvatar, Team, int, int, String)} — constructs a {@link
 *       TeamAvatarProposal} with {@code teamId=null} (DEC-59 Clause C / AC10 — teamId is written
 *       only by the operator-confirmation handler).
 *   <li>{@link #getPointsOrMin(UUID, Map)} — look up ratings by avatarId, fallback to {@link
 *       Integer#MIN_VALUE} for unrated avatars.
 * </ul>
 *
 * <p>Placed in the internal package per DEC-35 (abstract base class, not a public surface type).
 *
 * @see TeamSortCalculator
 * @see <a href="DEC-35">DEC-35 — impl in .internal</a>
 * @see <a href="DEC-59">DEC-59 — operator-confirmation workflow; teamId not set by calculator</a>
 * @see <a href="DEC-73">DEC-73 D-3 — AbstractAssignmentProposalCalculator base</a>
 * @see <a href="E58S03">E58S03 — AC3</a>
 */
abstract class AbstractAssignmentProposalCalculator implements TeamSortCalculator {

    /**
     * Resolves the {@link Team} aggregate for the given avatar's {@code teamId}.
     *
     * @param avatar the avatar whose team to resolve
     * @param teamById lookup map from teamId → Team (pre-built by caller)
     * @return the resolved Team; never {@code null}
     * @throws IllegalStateException if the team cannot be found (corrupt data defense per E48S20)
     */
    protected Team requireTeamForDisplay(TeamAvatar avatar, Map<UUID, Team> teamById) {
        UUID teamId = avatar.getTeamId();
        Team team = teamById.get(teamId);
        if (team == null) {
            throw new IllegalStateException(
                    "Cannot resolve Team for display: no Team found for teamId="
                            + teamId
                            + " (avatar.id="
                            + avatar.getId()
                            + "). Data integrity issue (AC-ERROR-MISSING-TEAM-DEFENSE, E48S20).");
        }
        return team;
    }

    /**
     * Constructs a {@link TeamAvatarProposal} for a Phase-2+ slot assignment.
     *
     * <p>{@code teamId} is passed through from the previous-phase avatar ({@code
     * fromAvatar.getTeamId()}) — this is the team identity that the calculator proposes to assign
     * to the target slot. Per AC10 / DEC-59 Clause C, the {@link TeamSortCalculator} does NOT write
     * teamId to the database; that write is the exclusive responsibility of the
     * operator-confirmation handler ({@code commitTransition}). The proposal is a read-only DTO
     * that carries the proposed teamId so the operator can review and confirm.
     *
     * @param fromAvatar the previous-phase avatar (source of teamId and structural identity fields)
     * @param team the resolved Team for display fields
     * @param targetGroup target group number in the next phase (1-based)
     * @param targetPosition target position within the group (1-based)
     * @param sortType the sortType string from the target DraftSection
     * @return a new proposal carrying the from-avatar's teamId (non-null when data is consistent)
     */
    protected TeamAvatarProposal buildProposal(
            TeamAvatar fromAvatar,
            Team team,
            int targetGroup,
            int targetPosition,
            String sortType) {
        // Pass through fromAvatar.teamId — the calculator does NOT write teamId to DB (AC10).
        // The teamId in the proposal DTO allows the operator-confirmation handler to identify
        // which team gets which slot when the operator confirms (commitTransition, DEC-59 Clause
        // C).
        return new TeamAvatarProposal(
                fromAvatar.getTeamId(),
                team.getTeamNumber(),
                team.getDescription(),
                targetGroup,
                targetPosition,
                fromAvatar.getGroupNumber(),
                fromAvatar.getGroupPosition(),
                sortType);
    }

    /**
     * Returns the avatar's rating points, or {@link Integer#MIN_VALUE} if no rating exists.
     *
     * <p>Used for sorting by descending points (higher points = better placement = lower position
     * number) in {@code placement_group} and {@code group_placement} modes.
     *
     * @param avatarId the avatar UUID to look up
     * @param ratingsByAvatarId pre-loaded ratings map (bulk-loaded via {@code findByPhaseId})
     * @return the avatar's points, or {@link Integer#MIN_VALUE} if not found
     */
    protected int getPointsOrMin(UUID avatarId, Map<UUID, TeamAvatarRating> ratingsByAvatarId) {
        TeamAvatarRating rating = ratingsByAvatarId.get(avatarId);
        return rating != null ? rating.getPoints() : Integer.MIN_VALUE;
    }
}
