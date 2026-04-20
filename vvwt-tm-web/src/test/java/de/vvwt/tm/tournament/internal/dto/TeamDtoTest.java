package de.vvwt.tm.tournament.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.Team;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Team DTOs in {@code de.vvwt.tm.tournament.internal.dto} (E21S04,
 * AC-TDD-TeamDTOs).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: DTOs at {@code de.vvwt.tm.tournament.internal.dto} (Team*) did
 * not exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>{@link TeamCreateRequest} — @NotBlank constraint on description; resolver defaults
 *   <li>{@link TeamBulkCreateRequest} — @NotNull constraint on teams list
 *   <li>{@link TeamUpdateRequest} — all-optional record; null fields mean "no change"
 *   <li>{@link TeamResponse} — {@code from(Team)} factory; {@code from(Team, boolean)} variant
 *   <li>{@link TeamBulkCreateResponse} — per-item success/failure structure
 * </ul>
 *
 * <p>Inventory lines 441–445 (five DTOs — confirmed, no 6th photo DTO).
 *
 * @see TeamCreateRequest
 * @see TeamBulkCreateRequest
 * @see TeamUpdateRequest
 * @see TeamResponse
 * @see TeamBulkCreateResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory lines 441–445)</a>
 */
@DisplayName("Team DTO unit tests — E21S04 AC-TDD-TeamDTOs")
class TeamDtoTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // =========================================================================
    // TeamCreateRequest
    // =========================================================================

    @Test
    @DisplayName("TeamCreateRequest — valid request has no constraint violations")
    void teamCreateRequest_valid_noViolations() {
        TeamCreateRequest req = new TeamCreateRequest("Mannschaft A", null, null, null, null);
        Set<ConstraintViolation<TeamCreateRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("TeamCreateRequest — blank description produces @NotBlank violation")
    void teamCreateRequest_blankDescription_producesViolation() {
        TeamCreateRequest req = new TeamCreateRequest("", null, null, null, null);
        Set<ConstraintViolation<TeamCreateRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo("description");
    }

    @Test
    @DisplayName("TeamCreateRequest — resolvedTeamNumber() returns 0 when teamNumber null")
    void teamCreateRequest_resolvedTeamNumber_returnsZeroWhenNull() {
        TeamCreateRequest req = new TeamCreateRequest("A", null, null, null, null);
        assertThat(req.resolvedTeamNumber()).isEqualTo(0);
    }

    @Test
    @DisplayName("TeamCreateRequest — resolvedTeamNumber() returns provided value when valid")
    void teamCreateRequest_resolvedTeamNumber_returnsValueWhenProvided() {
        TeamCreateRequest req = new TeamCreateRequest("A", 5, null, null, null);
        assertThat(req.resolvedTeamNumber()).isEqualTo(5);
    }

    @Test
    @DisplayName("TeamCreateRequest — resolvedParticipate() defaults to true when null")
    void teamCreateRequest_resolvedParticipate_defaultsToTrue() {
        TeamCreateRequest req = new TeamCreateRequest("A", null, null, null, null);
        assertThat(req.resolvedParticipate()).isTrue();
    }

    @Test
    @DisplayName("TeamCreateRequest — resolvedRefereeAssignment() defaults to false when null")
    void teamCreateRequest_resolvedRefereeAssignment_defaultsToFalse() {
        TeamCreateRequest req = new TeamCreateRequest("A", null, null, null, null);
        assertThat(req.resolvedRefereeAssignment()).isFalse();
    }

    @Test
    @DisplayName("TeamCreateRequest — resolvedWithoutAssessment() defaults to false when null")
    void teamCreateRequest_resolvedWithoutAssessment_defaultsToFalse() {
        TeamCreateRequest req = new TeamCreateRequest("A", null, null, null, null);
        assertThat(req.resolvedWithoutAssessment()).isFalse();
    }

    // =========================================================================
    // TeamBulkCreateRequest
    // =========================================================================

    @Test
    @DisplayName("TeamBulkCreateRequest — null teams list produces @NotNull violation")
    void teamBulkCreateRequest_nullTeams_producesViolation() {
        TeamBulkCreateRequest req = new TeamBulkCreateRequest(null);
        Set<ConstraintViolation<TeamBulkCreateRequest>> violations = validator.validate(req);
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("TeamBulkCreateRequest — valid non-empty list has no violations")
    void teamBulkCreateRequest_validList_noViolations() {
        TeamCreateRequest item = new TeamCreateRequest("A", null, null, null, null);
        TeamBulkCreateRequest req = new TeamBulkCreateRequest(List.of(item));
        Set<ConstraintViolation<TeamBulkCreateRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    // =========================================================================
    // TeamUpdateRequest
    // =========================================================================

    @Test
    @DisplayName("TeamUpdateRequest — all null fields allowed (partial update semantics)")
    void teamUpdateRequest_allNullFields_noViolations() {
        TeamUpdateRequest req = new TeamUpdateRequest(null, null, null, null, null);
        Set<ConstraintViolation<TeamUpdateRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("TeamUpdateRequest — resolvedTeamNumber() returns 0 when null")
    void teamUpdateRequest_resolvedTeamNumber_returnsZeroWhenNull() {
        TeamUpdateRequest req = new TeamUpdateRequest(null, null, null, null, null);
        assertThat(req.resolvedTeamNumber()).isEqualTo(0);
    }

    // =========================================================================
    // TeamResponse
    // =========================================================================

    @Test
    @DisplayName("TeamResponse.from(Team) — maps all entity fields to response")
    void teamResponse_from_mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        Team team = new Team();
        team.setId(id);
        team.setTournamentId(tournamentId);
        team.setTeamNumber(3);
        team.setDescription("Gamma");
        team.setParticipate(true);
        team.setRefereeAssignment(false);
        team.setWithoutAssessment(true);
        team.setCreatedAt(now);

        TeamResponse response = TeamResponse.from(team);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.tournamentId()).isEqualTo(tournamentId);
        assertThat(response.teamNumber()).isEqualTo(3);
        assertThat(response.description()).isEqualTo("Gamma");
        assertThat(response.participate()).isTrue();
        assertThat(response.refereeAssignment()).isFalse();
        assertThat(response.withoutAssessment()).isTrue();
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.hasPhoto()).isFalse(); // default no-photo factory
    }

    @Test
    @DisplayName("TeamResponse.from(Team, boolean) — hasPhoto set to true when supplied")
    void teamResponse_fromWithPhoto_hasPhotoTrue() {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setTournamentId(UUID.randomUUID());
        team.setDescription("Delta");

        TeamResponse response = TeamResponse.from(team, true);
        assertThat(response.hasPhoto()).isTrue();
    }

    // =========================================================================
    // TeamBulkCreateResponse
    // =========================================================================

    @Test
    @DisplayName("TeamBulkCreateResponse — success item has team, no error message")
    void teamBulkCreateResponse_successItem_hasTeamNoError() {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setTournamentId(UUID.randomUUID());
        team.setDescription("Success");

        de.vvwt.tm.tournament.internal.TeamService.BulkCreateResult domainResult =
                de.vvwt.tm.tournament.internal.TeamService.BulkCreateResult.success(team);
        TeamBulkCreateResponse response =
                TeamBulkCreateResponse.from(List.of(domainResult));

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).success()).isTrue();
        assertThat(response.results().get(0).team()).isNotNull();
        assertThat(response.results().get(0).errorMessage()).isNull();
    }

    @Test
    @DisplayName("TeamBulkCreateResponse — failure item has error message, no team")
    void teamBulkCreateResponse_failureItem_hasErrorNoTeam() {
        de.vvwt.tm.tournament.internal.TeamService.BulkCreateRequest req =
                new de.vvwt.tm.tournament.internal.TeamService.BulkCreateRequest(
                        "Fail", 1, true, false, false);
        de.vvwt.tm.tournament.internal.TeamService.BulkCreateResult domainResult =
                de.vvwt.tm.tournament.internal.TeamService.BulkCreateResult.error(req, "conflict");
        TeamBulkCreateResponse response =
                TeamBulkCreateResponse.from(List.of(domainResult));

        assertThat(response.results().get(0).success()).isFalse();
        assertThat(response.results().get(0).team()).isNull();
        assertThat(response.results().get(0).errorMessage()).isEqualTo("conflict");
    }
}
