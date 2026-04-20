package de.vvwt.tm.tournament.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.Tournament;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for tournament DTOs in {@code de.vvwt.tm.tournament.internal.dto} (E21S02).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: DTOs at {@code de.vvwt.tm.tournament.internal.dto} did not exist
 * at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>{@link TournamentCreateRequest} — @NotBlank/@NotNull/@Min constraints
 *   <li>{@link TournamentResponse} — {@code from(Tournament)} factory method
 *   <li>{@link TournamentUpdateRequest} — partial-update record; all-nullable fields
 * </ul>
 *
 * @see TournamentCreateRequest
 * @see TournamentResponse
 * @see TournamentUpdateRequest
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal.dto package</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 */
@DisplayName("Tournament DTO unit tests — E21S02 AC-TDD-DTOs")
class TournamentDtoTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // =========================================================================
    // TournamentCreateRequest
    // =========================================================================

    @Test
    @DisplayName("TournamentCreateRequest: valid request has no violations")
    void createRequest_valid_noViolations() {
        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "Hallenturnier 2026",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        Set<ConstraintViolation<TournamentCreateRequest>> violations = validator.validate(req);
        assertThat(violations).as("Valid request must produce no constraint violations").isEmpty();
    }

    @Test
    @DisplayName("TournamentCreateRequest: blank description produces @NotBlank violation")
    void createRequest_blankDescription_producesViolation() {
        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        Set<ConstraintViolation<TournamentCreateRequest>> violations = validator.validate(req);
        assertThat(violations)
                .as("Blank description must produce at least one @NotBlank violation")
                .isNotEmpty();
        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()))
                .contains("description");
    }

    @Test
    @DisplayName("TournamentCreateRequest: teamCount below 2 produces @Min violation")
    void createRequest_teamCountBelowMin_producesViolation() {
        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "Tournament",
                        null,
                        1, // below @Min(2)
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        Set<ConstraintViolation<TournamentCreateRequest>> violations = validator.validate(req);
        assertThat(violations).as("teamCount=1 must produce a @Min violation").isNotEmpty();
        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()))
                .contains("teamCount");
    }

    @Test
    @DisplayName("TournamentCreateRequest: fieldCount below 1 produces @Min violation")
    void createRequest_fieldCountBelowMin_producesViolation() {
        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "Tournament",
                        null,
                        4,
                        0, // below @Min(1)
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        Set<ConstraintViolation<TournamentCreateRequest>> violations = validator.validate(req);
        assertThat(violations).as("fieldCount=0 must produce a @Min violation").isNotEmpty();
        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()))
                .contains("fieldCount");
    }

    // =========================================================================
    // TournamentResponse
    // =========================================================================

    @Test
    @DisplayName("TournamentResponse.from() maps all fields from Tournament entity")
    void response_from_mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 4, 20, 10, 0);
        LocalTime startTime = LocalTime.of(9, 0);

        Tournament t = new Tournament();
        t.setId(id);
        t.setTenantId(tenantId);
        t.setDescription("Test Tournament");
        t.setMatchFormat("BEST_OF_3");
        t.setScoringRuleId("setPoints");
        t.setSetValidationRuleId("standardVolleyball");
        t.setMatchGeneratorId("roundRobin");
        t.setStatus("DRAFT");
        t.setCreatedAt(now);
        t.setFieldCount(3);
        t.setTeamCount(6);
        t.setPlannedStartTime(startTime);

        TournamentResponse response = TournamentResponse.from(t);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.description()).isEqualTo("Test Tournament");
        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.matchFormat()).isEqualTo("BEST_OF_3");
        assertThat(response.fieldCount()).isEqualTo(3);
        assertThat(response.teamCount()).isEqualTo(6);
        assertThat(response.scoringRuleId()).isEqualTo("setPoints");
        assertThat(response.setValidationRuleId()).isEqualTo("standardVolleyball");
        assertThat(response.matchGeneratorId()).isEqualTo("roundRobin");
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.plannedStartTime()).isEqualTo(startTime);
    }

    @Test
    @DisplayName("TournamentResponse.from() does NOT expose tenantId")
    void response_from_doesNotExposeTenantId() {
        // TournamentResponse must not have a tenantId() accessor — it is intentionally excluded
        // from the response DTO (tenantId is an internal implementation detail, not client-visible)
        Tournament t = new Tournament();
        t.setId(UUID.randomUUID());
        t.setTenantId(UUID.randomUUID());
        t.setDescription("Test");
        t.setMatchFormat("BEST_OF_1");
        t.setScoringRuleId("setPoints");
        t.setSetValidationRuleId("standardVolleyball");
        t.setMatchGeneratorId("roundRobin");
        t.setStatus("DRAFT");
        t.setFieldCount(1);
        t.setTeamCount(2);

        TournamentResponse response = TournamentResponse.from(t);

        // Verify that TournamentResponse record has no tenantId field
        assertThat(response.getClass().getRecordComponents())
                .as("TournamentResponse must not expose tenantId")
                .extracting(rc -> rc.getName())
                .doesNotContain("tenantId");
    }

    // =========================================================================
    // TournamentUpdateRequest
    // =========================================================================

    @Test
    @DisplayName(
            "TournamentUpdateRequest: all-null update request is valid (partial-update semantics)")
    void updateRequest_allNull_isValid() {
        TournamentUpdateRequest req =
                new TournamentUpdateRequest(null, null, null, null, null, null, null, null, null);

        Set<ConstraintViolation<TournamentUpdateRequest>> violations = validator.validate(req);
        assertThat(violations)
                .as("All-null update request must be valid (partial update — no changes)")
                .isEmpty();
    }

    @Test
    @DisplayName("TournamentUpdateRequest: teamCount below 2 produces @Min violation")
    void updateRequest_teamCountBelowMin_producesViolation() {
        TournamentUpdateRequest req =
                new TournamentUpdateRequest(null, null, 1, null, null, null, null, null, null);

        Set<ConstraintViolation<TournamentUpdateRequest>> violations = validator.validate(req);
        assertThat(violations)
                .as("teamCount=1 in update request must produce a @Min violation")
                .isNotEmpty();
        assertThat(violations.stream().map(v -> v.getPropertyPath().toString()))
                .contains("teamCount");
    }

    @Test
    @DisplayName("TournamentUpdateRequest: plannedStartTime can be set and retrieved")
    void updateRequest_plannedStartTime_canBeSetAndRetrieved() {
        LocalTime startTime = LocalTime.of(9, 30);
        TournamentUpdateRequest req =
                new TournamentUpdateRequest(
                        null, null, null, null, null, null, null, null, startTime);

        assertThat(req.plannedStartTime()).isEqualTo(startTime);
    }
}
