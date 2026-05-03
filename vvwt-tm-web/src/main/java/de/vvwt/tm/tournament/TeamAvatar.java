package de.vvwt.tm.tournament;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TeamAvatar entity — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Maps to the {@code team_avatar} table (V2 migration). Lives at {@code
 * de.vvwt.tm.tournament.TeamAvatar} — the Modulith public API package per DEC-21 §Module layout.
 * Does NOT import the legacy {@code de.vvwt.tm.domain.TeamAvatar}.
 *
 * <h2>Structural identity (DEC-9)</h2>
 *
 * <p>A TeamAvatar slot is uniquely identified by the composite key {@code (tournamentId, phaseId,
 * groupNumber, groupPosition)}. This structural identity is enforced by the DB UNIQUE constraint
 * {@code uq_team_avatar_structural_identity} in V2. UUIDs do not cross the optimizer boundary — the
 * structural key is the only safe cross-boundary identity.
 *
 * <p>Inventory line 455 ({@code TeamAvatar}).
 *
 * @see TeamAvatarRepository
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith adoption</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 455)</a>
 */
public class TeamAvatar {

    private UUID id;

    private UUID tournamentId;

    /** Structural identity field (DEC-9). */
    private UUID phaseId;

    /** Structural identity field (DEC-9). */
    private int groupNumber;

    /** Structural identity field (DEC-9). */
    private int groupPosition;

    private UUID teamId;

    /** Optional label for this avatar slot. */
    private String description;

    private LocalDateTime createdAt;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Default no-arg constructor for Spring Data / JdbcTemplate row mapping. */
    public TeamAvatar() {}

    /**
     * Full constructor for explicit creation in service / repository code.
     *
     * @param id entity PK
     * @param tournamentId owning tournament
     * @param phaseId structural identity — phase (DEC-9)
     * @param groupNumber structural identity — group (DEC-9)
     * @param groupPosition structural identity — position within group (DEC-9)
     * @param teamId the team assigned to this slot
     * @param description optional label
     * @param createdAt creation timestamp
     */
    public TeamAvatar(
            UUID id,
            UUID tournamentId,
            UUID phaseId,
            int groupNumber,
            int groupPosition,
            UUID teamId,
            String description,
            LocalDateTime createdAt) {
        this.id = id;
        this.tournamentId = tournamentId;
        this.phaseId = phaseId;
        this.groupNumber = groupNumber;
        this.groupPosition = groupPosition;
        this.teamId = teamId;
        this.description = description;
        this.createdAt = createdAt;
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTournamentId() {
        return tournamentId;
    }

    public void setTournamentId(UUID tournamentId) {
        this.tournamentId = tournamentId;
    }

    public UUID getPhaseId() {
        return phaseId;
    }

    public void setPhaseId(UUID phaseId) {
        this.phaseId = phaseId;
    }

    public int getGroupNumber() {
        return groupNumber;
    }

    public void setGroupNumber(int groupNumber) {
        this.groupNumber = groupNumber;
    }

    public int getGroupPosition() {
        return groupPosition;
    }

    public void setGroupPosition(int groupPosition) {
        this.groupPosition = groupPosition;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
