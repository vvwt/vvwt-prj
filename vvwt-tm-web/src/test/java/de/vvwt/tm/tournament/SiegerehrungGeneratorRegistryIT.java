package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.PhasePreparationService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test verifying that the {@code siegerehrungMatchGenerator} bean is registered and
 * picked up by {@link MatchGeneratorRegistry}, and that {@link
 * PhasePreparationService#generateMatches(UUID, String)} with key {@code "siegerehrung"} succeeds
 * without exception and produces an empty match list.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC-IMPL-MATCHGENERATOR-REGISTRY-PICKUP
 *   <li>AC-TEST-PHASE-PREPARATION-SIEGEREHRUNG-INTEGRATION-GREEN
 * </ul>
 *
 * <h2>Why {@code @SpringBootTest} (DEC-38 Clause C analogy)</h2>
 *
 * <p>This IT requires the full tenant-routing DataSource stack ({@code TenantContext} + {@code
 * RoutingTenantDataSource} + per-tenant Flyway migrations) to operate {@link JdbcTemplate} against
 * a tenant-scoped H2 database for test data setup and teardown. The same pattern is used by all
 * other bounded-context ITs in the {@code tournament} module (e.g., {@link
 * TournamentLifecycleServiceIT}, {@link TournamentLifecycleLockIT}) where tenant-routing DB access
 * is needed. {@code @ApplicationModuleTest(ALL_DEPENDENCIES)} does not wire the full tenant
 * infrastructure (routing DataSource + Flyway bootstrap) in this codebase; {@code @SpringBootTest}
 * with {@code @Import(TenantContextTestSupport.class)} is the established pattern.
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test class is in {@code de.vvwt.tm.tournament} (the public API package). The {@link
 * MatchGeneratorRegistry} is also in the public package — no cross-package concern. {@link
 * PhasePreparationService} is in {@code tournament.internal}; this test injects it as a Spring bean
 * (it has no public interface — it is internal-use only per E21S08). Cross-package typing per
 * DEC-36 does not require an interface where none was authored for the service.
 *
 * @see MatchGeneratorRegistry
 * @see de.vvwt.tm.tournament.internal.SiegerehrungMatchGenerator
 * @see de.vvwt.tm.tournament.internal.PhasePreparationService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first before bean registration)</a>
 * @see <a href="DEC-38">DEC-38 Clause C analogy — full @SpringBootTest for tenant-routing DB
 *     ITs</a>
 * @see <a href="E48S02">E48S02 — AC-IMPL-MATCHGENERATOR-REGISTRY-PICKUP,
 *     AC-TEST-PHASE-PREPARATION-SIEGEREHRUNG-INTEGRATION-GREEN</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:siegerehrungregistryit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("SiegerehrungMatchGenerator — registry pickup + generateMatches IT")
class SiegerehrungGeneratorRegistryIT {

    @Autowired private MatchGeneratorRegistry matchGeneratorRegistry;

    @Autowired private PhasePreparationService phasePreparationService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private PhaseRepository phaseRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID phaseId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        locationId = tenantBinder.getDefaultLocationId();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();

        // Insert tournament with match_generator_id = "siegerehrung"
        jdbcTemplate.update(
                "INSERT INTO tournament (id, description, match_format, scoring_rule_id,"
                        + " set_validation_rule_id, match_generator_id, status, location_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                "Siegerehrung Test Tournament",
                "BEST_OF_3",
                "combinedSetsScoring",
                "threeSetValidation",
                "siegerehrung",
                "ACTIVE",
                locationId);

        // Insert phase for this tournament
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Siegerehrung",
                "PENDING",
                0);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM match WHERE phase_id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE phase_id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM phase WHERE id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        tenantBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // AC-IMPL-MATCHGENERATOR-REGISTRY-PICKUP
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "MatchGeneratorRegistry.get('siegerehrung') resolves the SiegerehrungMatchGenerator"
                    + " bean")
    void registry_getSiegerehrung_resolvesSiegerehrungMatchGeneratorBean() {
        MatchGenerator generator = matchGeneratorRegistry.get("siegerehrung");

        assertThat(generator).isNotNull();
        assertThat(generator.getBeanId()).isEqualTo("siegerehrung");
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PHASE-PREPARATION-SIEGEREHRUNG-INTEGRATION-GREEN
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "generateMatches for 'siegerehrung' phase produces no exception and an empty match"
                    + " list")
    void generateMatches_siegerehrungKey_noExceptionAndEmptyMatchList() {
        // Act: no-op generator must not throw
        assertThatNoException()
                .isThrownBy(() -> phasePreparationService.generateMatches(phaseId, "siegerehrung"));

        // Assert: no matches were persisted (DEC-26 Rule 2 — verify via independent DB query)
        Integer matchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phaseId);
        assertThat(matchCount).isZero();
    }
}
