// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.certificate.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * White-box unit test for {@link DefaultLocaleResolver} — E46S02, DEC-36 same-package exemption.
 *
 * <p>This test class is in the SAME package as {@link DefaultLocaleResolver} ({@code
 * de.vvwt.tm.certificate.internal}) and therefore MAY reference the implementation class directly
 * per DEC-36's same-package exemption. The SUT is declared as {@link DefaultLocaleResolver}
 * (implementation type) rather than the public interface type.
 *
 * <h2>Purpose</h2>
 *
 * <p>White-box verification that {@link DefaultLocaleResolver} correctly delegates to the {@link
 * JdbcTemplate} collaborators for each language surface. Uses a real {@link DataSource} (in-memory
 * H2) seeded via {@link TenantDaoTestSupport} to exercise the JdbcTemplate path directly without
 * requiring a Spring context.
 *
 * <h2>DEC-22 RED-first provenance</h2>
 *
 * <p>This file was committed BEFORE the {@link DefaultLocaleResolver} class was written (Iron Law
 * verification step: compile failure before production code).
 *
 * @see DefaultLocaleResolver
 * @see de.vvwt.tm.certificate.LocaleResolverContractTest
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultLocaleResolver white-box unit tests — E46S02")
class DefaultLocaleResolverTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DefaultLocaleResolver resolver;

    private UUID tournamentId;
    private UUID teamId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        dataSource = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);

        resolver = new DefaultLocaleResolver(dataSource);

        // Seed minimal fixture: tenants row (required for the tenant-language read)
        locationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        // Insert locations row (FK dep from tenants)
        jdbc.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId.toString(),
                "Test Location");

        // Insert tenants row with no language (NULL); tenant_location_count = 1 (CHECK >= 1)
        jdbc.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, language)"
                        + " VALUES (?, ?, 1, NULL)",
                tenantId.toString(),
                "Test Tenant");

        tournamentId = UUID.randomUUID();
        teamId = UUID.randomUUID();

        // Insert tournament with required NOT NULL columns; language = NULL (chain falls through)
        // V2 adds the language column via E46S01 migration (applied by applyTournamentSchema).
        jdbc.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " language) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                tournamentId.toString(),
                locationId.toString(),
                "Test Tournament",
                "SETS_OF_25",
                "STANDARD",
                "STANDARD",
                "ROUND_ROBIN");

        // Insert team with language = NULL (chain falls through)
        // V2 adds the language column via E46S01 migration.
        jdbc.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " referee_assignment, without_assessment, language)"
                        + " VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE, NULL)",
                teamId.toString(),
                tournamentId.toString(),
                1,
                "Team One");
    }

    @Test
    @DisplayName(
            "resolveForTeam: team language set — returns team locale (JdbcTemplate delegates"
                    + " correctly)")
    void resolveForTeam_teamLanguageSet_returnsTeamLocale() {
        jdbc.update("UPDATE team SET language = 'en' WHERE id = ?", teamId.toString());
        assertThat(resolver.resolveForTeam(tournamentId, teamId))
                .isEqualTo(Locale.forLanguageTag("en"));
    }

    @Test
    @DisplayName("resolveForTeam: team null, tournament set — returns tournament locale")
    void resolveForTeam_teamNull_tournamentSet_returnsTournamentLocale() {
        jdbc.update("UPDATE tournament SET language = 'fr' WHERE id = ?", tournamentId.toString());
        assertThat(resolver.resolveForTeam(tournamentId, teamId))
                .isEqualTo(Locale.forLanguageTag("fr"));
    }

    @Test
    @DisplayName("resolveForTeam: team null, tournament null, tenant set — returns tenant locale")
    void resolveForTeam_allNull_tenantSet_returnsTenantLocale() {
        jdbc.update("UPDATE tenants SET language = 'it'");
        assertThat(resolver.resolveForTeam(tournamentId, teamId))
                .isEqualTo(Locale.forLanguageTag("it"));
    }

    @Test
    @DisplayName("resolveForTeam: all null — returns system default 'de'")
    void resolveForTeam_allNull_returnsSystemDefault() {
        assertThat(resolver.resolveForTeam(tournamentId, teamId))
                .isEqualTo(Locale.forLanguageTag("de"));
    }

    @Test
    @DisplayName("resolveForTournament: tournament language set — returns tournament locale")
    void resolveForTournament_tournamentLanguageSet_returnsTournamentLocale() {
        jdbc.update("UPDATE tournament SET language = 'ja' WHERE id = ?", tournamentId.toString());
        assertThat(resolver.resolveForTournament(tournamentId))
                .isEqualTo(Locale.forLanguageTag("ja"));
    }

    @Test
    @DisplayName("resolveForTournament: all null — returns system default 'de'")
    void resolveForTournament_allNull_returnsSystemDefault() {
        assertThat(resolver.resolveForTournament(tournamentId))
                .isEqualTo(Locale.forLanguageTag("de"));
    }
}
