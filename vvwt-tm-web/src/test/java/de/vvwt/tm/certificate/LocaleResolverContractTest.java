// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Interface contract test for {@link LocaleResolver} — E46S02, DEC-22 Q-1a RED-first.
 *
 * <p>Authored RED-first against the not-yet-implemented {@link
 * de.vvwt.tm.certificate.internal.DefaultLocaleResolver}: this file was committed BEFORE the
 * implementation was written (DEC-22 Iron Law). The class was verified to be RED (compile failure)
 * before the production code was added.
 *
 * <h2>Test classification (DEC-41)</h2>
 *
 * <p>Spec-Anchored / Named-algebraic-invariant: the language chain surfaces (team, tournament,
 * tenant, system default, blank variants) are explicit named cases derived from the invariant
 * specification in Brief D-7 + AC-RESOLVER-CHAIN-CORRECT-FOR-TEAM /
 * AC-RESOLVER-CHAIN-CORRECT-FOR-TOURNAMENT.
 *
 * <h2>DEC-36 cross-package typing</h2>
 *
 * <p>This test class is in package {@code de.vvwt.tm.certificate} (public, NOT {@code internal}).
 * The SUT field is declared as the public interface type {@link LocaleResolver} — never as {@code
 * DefaultLocaleResolver} (AC-CONTRACT-TEST-INTERFACE-TYPED).
 *
 * <h2>DEC-38 + DEC-44 annotation choice — @SpringBootTest exception</h2>
 *
 * <p>Uses {@code @SpringBootTest} (not {@code @ApplicationModuleTest}) because certificate's {@code
 * allowedDependencies = {tournament, photo}} does not include {@code tenant}. Even {@code
 * ALL_DEPENDENCIES} mode only loads certificate + tournament + photo module beans; the tenant
 * infrastructure ({@link TenantContextTestSupport}, routing {@link javax.sql.DataSource}) is
 * absent. All other TenantContext-requiring ITs in the project use {@code @SpringBootTest} for the
 * same reason (empirically verified). DEC-44 does not apply — this is a certificate IT, not a
 * web-module IT.
 *
 * <h2>TenantContext binding (AC-RESOLVER-REQUIRES-BOUND-TENANT-CONTEXT)</h2>
 *
 * <p>The contract test binds the default-tenant {@link TenantContext} at {@code @BeforeEach} via
 * {@link TenantContextTestSupport.Binder#bindDefaultTenant()}. An explicit test verifies the
 * fail-fast behaviour when called outside a bound context.
 *
 * @see LocaleResolver
 * @see de.vvwt.tm.certificate.internal.DefaultLocaleResolver
 * @see TenantDaoTestSupport
 * @see TenantContextTestSupport
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("LocaleResolver contract tests — E46S02")
class LocaleResolverContractTest {

    /** SUT declared as the public interface type per DEC-36 (AC-CONTRACT-TEST-INTERFACE-TYPED). */
    @Autowired LocaleResolver resolver;

    @Autowired TenantContextTestSupport.Binder tenantContextBinder;

    @Autowired TenantContext tenantContext;

    /**
     * Routing DataSource injected for direct JDBC seed operations (DEC-26 Rule 3 — read-path
     * fixtures via direct JDBC, not via the resolver or other application beans).
     */
    @Autowired javax.sql.DataSource dataSource;

    private UUID tenantId;
    private UUID tournamentId;
    private UUID teamId;

    @BeforeEach
    void bindTenantAndSeedFixtures() {
        // Bind the default tenant (AC-RESOLVER-REQUIRES-BOUND-TENANT-CONTEXT)
        tenantId = tenantContextBinder.bindDefaultTenant();

        // Create a tournament and team row via direct JDBC (DEC-26 Rule 3)
        UUID locationId = tenantContextBinder.getDefaultLocationId();
        tournamentId = UUID.randomUUID();
        teamId = UUID.randomUUID();

        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);

        // Reset tenant language to NULL before each test (DEC-5 shared-state hygiene).
        // Tests that call setTenantLanguage() leave a non-null value in the shared per-tenant DB
        // (the Spring context is cached across tests). Resetting here ensures the "all surfaces
        // null → system default 'de'" tests see a clean state regardless of test execution order.
        jdbc.update("UPDATE tenants SET language = NULL");

        // Insert tournament with required NOT NULL columns; language = NULL (chain falls through)
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

        // Insert team with no language (NULL — chain falls through)
        jdbc.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " referee_assignment, without_assessment, language)"
                        + " VALUES (?, ?, ?, ?, TRUE, FALSE, FALSE, NULL)",
                teamId.toString(),
                tournamentId.toString(),
                1,
                "Team One");
    }

    @AfterEach
    void unbindTenant() {
        tenantContextBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // resolveForTeam — chain surface tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resolveForTeam: when team.language is set, returns team locale")
    void resolveForTeam_whenTeamLanguageSet_returnsTeamLocale() {
        setTeamLanguage("en");
        Locale result = resolver.resolveForTeam(tournamentId, teamId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("en"));
    }

    @Test
    @DisplayName(
            "resolveForTeam: when team.language is null and tournament.language is set, returns"
                    + " tournament locale")
    void resolveForTeam_whenTeamNullAndTournamentLanguageSet_returnsTournamentLocale() {
        // team.language remains NULL (set in setUp)
        setTournamentLanguage("fr");
        Locale result = resolver.resolveForTeam(tournamentId, teamId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("fr"));
    }

    @Test
    @DisplayName(
            "resolveForTeam: when team and tournament null, and tenant.language is set, returns"
                    + " tenant locale")
    void resolveForTeam_whenTeamAndTournamentNullAndTenantLanguageSet_returnsTenantLocale() {
        // team.language and tournament.language remain NULL (set in setUp)
        setTenantLanguage("it");
        Locale result = resolver.resolveForTeam(tournamentId, teamId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("it"));
    }

    @Test
    @DisplayName("resolveForTeam: when all surfaces are null, returns system default 'de'")
    void resolveForTeam_whenAllSurfacesNull_returnsSystemDefaultDe() {
        // team, tournament, tenant all NULL (set in setUp; tenant.language remains NULL from V1)
        Locale result = resolver.resolveForTeam(tournamentId, teamId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("de"));
    }

    @Test
    @DisplayName(
            "resolveForTeam: when team.language is blank (empty string), advances to tournament"
                    + " surface")
    void resolveForTeam_whenTeamLanguageBlank_advancesToTournament() {
        setTeamLanguage("");
        setTournamentLanguage("nl");
        Locale result = resolver.resolveForTeam(tournamentId, teamId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("nl"));
    }

    @Test
    @DisplayName(
            "resolveForTeam: when team.language is whitespace-only, advances to tournament surface")
    void resolveForTeam_whenTeamLanguageWhitespaceOnly_advancesToTournament() {
        setTeamLanguage("   ");
        setTournamentLanguage("es");
        Locale result = resolver.resolveForTeam(tournamentId, teamId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("es"));
    }

    @Test
    @DisplayName("resolveForTeam: when tournament.language is blank, advances to tenant surface")
    void resolveForTeam_whenTournamentLanguageBlank_advancesToTenant() {
        setTournamentLanguage("");
        setTenantLanguage("pt");
        Locale result = resolver.resolveForTeam(tournamentId, teamId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("pt"));
    }

    @Test
    @DisplayName("resolveForTeam: when tenant.language is blank, returns system default 'de'")
    void resolveForTeam_whenTenantLanguageBlank_returnsSystemDefaultDe() {
        setTenantLanguage("   ");
        Locale result = resolver.resolveForTeam(tournamentId, teamId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("de"));
    }

    @Test
    @DisplayName(
            "resolveForTeam: when called outside bound TenantContext, propagates exception with"
                    + " IllegalStateException cause")
    void resolveForTeam_outsideBoundTenantContext_throwsIllegalStateException() {
        // Unbind first (AC-RESOLVER-REQUIRES-BOUND-TENANT-CONTEXT)
        tenantContextBinder.unbind();
        // Spring JDBC wraps the IllegalStateException from TenantContext.current() (thrown by the
        // routing DataSource) in a CannotGetJdbcConnectionException — the cause IS an
        // IllegalStateException. The resolver cannot import TenantContext directly (certificate
        // allowedDependencies = {tournament, photo} — AC-NO-NEW-CROSS-CONTEXT-EDGE), so
        // fail-fast is guaranteed by the routing DataSource, not by an explicit pre-check.
        assertThatThrownBy(() -> resolver.resolveForTeam(tournamentId, teamId))
                .hasCauseInstanceOf(IllegalStateException.class);
        // Re-bind so @AfterEach unbind is a no-op (idempotent)
        tenantContextBinder.bindDefaultTenant();
    }

    @Test
    @DisplayName(
            "resolveForTeam: when tenants row is missing, throws IllegalStateException (invariant"
                    + " violation)")
    void resolveForTeam_whenTenantsRowMissing_throwsIllegalStateException() {
        // Drop the tenants row to simulate invariant violation (DEC-5 — exactly one row per DB)
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM tenants");

        assertThatThrownBy(() -> resolver.resolveForTeam(tournamentId, teamId))
                .isInstanceOf(IllegalStateException.class);

        // Restore the tenants row so subsequent tests in this class see a consistent per-tenant DB
        // state (DEC-5: exactly one row per DB). The Spring context is shared across tests in the
        // same run — destructive mutations must be undone before the test method exits.
        jdbc.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default)"
                        + " VALUES (?, 'Default (LAN)', 1, TRUE)",
                tenantId.toString());
    }

    // -------------------------------------------------------------------------
    // resolveForTournament — chain surface tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resolveForTournament: when tournament.language is set, returns tournament locale")
    void resolveForTournament_whenTournamentLanguageSet_returnsTournamentLocale() {
        setTournamentLanguage("ja");
        Locale result = resolver.resolveForTournament(tournamentId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("ja"));
    }

    @Test
    @DisplayName(
            "resolveForTournament: when tournament.language is null and tenant.language is set,"
                    + " returns tenant locale")
    void resolveForTournament_whenTournamentNullAndTenantLanguageSet_returnsTenantLocale() {
        // tournament.language remains NULL (set in setUp)
        setTenantLanguage("ko");
        Locale result = resolver.resolveForTournament(tournamentId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("ko"));
    }

    @Test
    @DisplayName("resolveForTournament: when all surfaces are null, returns system default 'de'")
    void resolveForTournament_whenAllSurfacesNull_returnsSystemDefaultDe() {
        Locale result = resolver.resolveForTournament(tournamentId);
        assertThat(result).isEqualTo(Locale.forLanguageTag("de"));
    }

    // -------------------------------------------------------------------------
    // Fixture helpers (DEC-26 Rule 3 — direct JDBC mutations)
    // -------------------------------------------------------------------------

    private void setTeamLanguage(String language) {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("UPDATE team SET language = ? WHERE id = ?", language, teamId.toString());
    }

    private void setTournamentLanguage(String language) {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update(
                "UPDATE tournament SET language = ? WHERE id = ?",
                language,
                tournamentId.toString());
    }

    private void setTenantLanguage(String language) {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("UPDATE tenants SET language = ?", language);
    }
}
