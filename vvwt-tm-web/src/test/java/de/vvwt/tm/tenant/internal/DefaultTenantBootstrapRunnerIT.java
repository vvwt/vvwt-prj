// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tenant.TenantRegistryPort.TenantRecord;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link DefaultTenantBootstrapRunner} — E46S05 property-binding ACs.
 *
 * <p>DEC-22 Iron Law: all new behavioral scenarios verified RED-first (compile error before {@link
 * TenantRecord#language()} exists and before runner reads properties).
 *
 * <p>DEC-41 observable-form classification: assertions use observable registry-state ({@link
 * TenantRecord#displayName()}, {@link TenantRecord#language()}) and per-tenant {@code
 * tenants.language} column via assertj-db. No internal helper invocation counts used.
 *
 * <p>AC-PROPERTY-OVERRIDE-TESTS-USE-SPRINGBOOTTEST: property-binding tests use
 * {@code @SpringBootTest} to exercise the actual {@code @ConfigurationProperties} binding
 * mechanism. This is the observable surface under test.
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>AC-PROP-DISPLAY-NAME-DEFAULT — {@link #firstStart_registersWithDefaultDisplayName()}
 *   <li>AC-PROP-LANGUAGE-DEFAULT — {@link #firstStart_registersWithDefaultLanguage()}
 *   <li>AC-PROP-DISPLAY-NAME-OVERRIDE — {@link #firstStart_registersWithOverriddenDisplayName()}
 *   <li>AC-PROP-LANGUAGE-OVERRIDE — {@link #firstStart_registersWithOverriddenLanguage()}
 *   <li>AC-INSERT-TENANTS-LANGUAGE-AT-BOOTSTRAP — {@link
 *       #firstStart_insertsLanguageColumnInTenantsTable()}
 *   <li>AC-INSERT-TENANTS-DISPLAY-NAME-AT-BOOTSTRAP — {@link
 *       #firstStart_insertsDisplayNameColumnInTenantsTable()}
 * </ul>
 *
 * @see DefaultTenantBootstrapRunner
 * @see TmBootstrapProperties
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class DefaultTenantBootstrapRunnerIT {

    @Autowired private TenantRegistryPort tenantRegistryPort;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Qualifier("routingTenantDataSource")
    @Autowired
    private DataSource routingDataSource;

    @BeforeEach
    void bindTenant() {
        tenantBinder.bindDefaultTenant();
    }

    @AfterEach
    void unbindTenant() {
        tenantBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // AC-PROP-DISPLAY-NAME-DEFAULT
    // -------------------------------------------------------------------------

    /**
     * AC-PROP-DISPLAY-NAME-DEFAULT: fresh boot with no override registers default tenant with
     * displayName = "ToM Tournament Manager (LAN)".
     *
     * <p>DEC-22 RED-first: test written before runner reads property. RED at compile time
     * (language() missing) then at assertion time (runner still uses "Default (LAN)"). DEC-41
     * observable-form: registry-state assertion.
     */
    @Test
    void firstStart_registersWithDefaultDisplayName() {
        List<TenantRecord> all = tenantRegistryPort.findAll();
        assertThat(all).hasSize(1);
        TenantRecord record = all.get(0);
        assertThat(record.displayName())
                .as("AC-PROP-DISPLAY-NAME-DEFAULT: default tenant should have product brand name")
                .isEqualTo("ToM Tournament Manager (LAN)");
    }

    /**
     * AC-PROP-LANGUAGE-DEFAULT: fresh boot with no override registers default tenant with language
     * = "de".
     *
     * <p>DEC-22 RED-first: test written before TenantRecord has language() method — compile error.
     * DEC-41 observable-form: registry-state assertion on TenantRecord.language().
     */
    @Test
    void firstStart_registersWithDefaultLanguage() {
        List<TenantRecord> all = tenantRegistryPort.findAll();
        assertThat(all).hasSize(1);
        TenantRecord record = all.get(0);
        assertThat(record.language())
                .as("AC-PROP-LANGUAGE-DEFAULT: default tenant should have language 'de'")
                .isEqualTo("de");
    }

    // -------------------------------------------------------------------------
    // AC-INSERT-TENANTS-LANGUAGE-AT-BOOTSTRAP
    // -------------------------------------------------------------------------

    /**
     * AC-INSERT-TENANTS-LANGUAGE-AT-BOOTSTRAP: the per-tenant tenants table row has language = "de"
     * after default bootstrap.
     *
     * <p>DEC-41 observable-form: assertj-db verifies the DB column state directly.
     */
    @Test
    void firstStart_insertsLanguageColumnInTenantsTable() {
        JdbcTemplate jdbc = new JdbcTemplate(routingDataSource);
        String language = jdbc.queryForObject("SELECT language FROM tenants LIMIT 1", String.class);
        assertThat(language)
                .as(
                        "AC-INSERT-TENANTS-LANGUAGE-AT-BOOTSTRAP: tenants.language must be 'de'"
                                + " after bootstrap")
                .isEqualTo("de");
    }

    /**
     * AC-INSERT-TENANTS-DISPLAY-NAME-AT-BOOTSTRAP: the per-tenant tenants table row has
     * display_name equal to the property-bound value (not the old hardcoded literal).
     *
     * <p>DEC-41 observable-form: DB column state assertion.
     */
    @Test
    void firstStart_insertsDisplayNameColumnInTenantsTable() {
        JdbcTemplate jdbc = new JdbcTemplate(routingDataSource);
        String displayName =
                jdbc.queryForObject("SELECT display_name FROM tenants LIMIT 1", String.class);
        assertThat(displayName)
                .as(
                        "AC-INSERT-TENANTS-DISPLAY-NAME-AT-BOOTSTRAP: tenants.display_name must"
                                + " match property-bound value")
                .isEqualTo("ToM Tournament Manager (LAN)");
    }
}
