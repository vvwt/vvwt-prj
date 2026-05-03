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
 * Integration tests for {@link DefaultTenantBootstrapRunner} — property OVERRIDE scenarios.
 *
 * <p>AC-PROPERTY-OVERRIDE-TESTS-USE-SPRINGBOOTTEST: these tests use {@code @SpringBootTest} with
 * {@code properties} to exercise the {@code @ConfigurationProperties} binding mechanism. The
 * property-binding surface ({@link TmBootstrapProperties}) is the observable subject.
 *
 * <p>DEC-22 RED-first: written before runner reads properties — compile error on language().
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>AC-PROP-DISPLAY-NAME-OVERRIDE — {@link #firstStart_registersWithOverriddenDisplayName()}
 *   <li>AC-PROP-LANGUAGE-OVERRIDE — {@link #firstStart_registersWithOverriddenLanguage()}
 * </ul>
 *
 * @see DefaultTenantBootstrapRunner
 * @see TmBootstrapProperties
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "tm.bootstrap.default-tenant.display-name=My Club",
            "tm.bootstrap.default-tenant.language=en"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class DefaultTenantBootstrapRunnerPropertyOverrideIT {

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

    /**
     * AC-PROP-DISPLAY-NAME-OVERRIDE: fresh boot with overridden display-name registers with the
     * configured value ("My Club").
     *
     * <p>DEC-41 observable-form: registry-state assertion on TenantRecord.displayName().
     */
    @Test
    void firstStart_registersWithOverriddenDisplayName() {
        List<TenantRecord> all = tenantRegistryPort.findAll();
        assertThat(all).hasSize(1);
        TenantRecord record = all.get(0);
        assertThat(record.displayName())
                .as(
                        "AC-PROP-DISPLAY-NAME-OVERRIDE: runner must use property-overridden display"
                                + " name")
                .isEqualTo("My Club");
    }

    /**
     * AC-PROP-LANGUAGE-OVERRIDE: fresh boot with overridden language registers with the configured
     * value ("en").
     *
     * <p>DEC-41 observable-form: registry-state assertion on TenantRecord.language().
     */
    @Test
    void firstStart_registersWithOverriddenLanguage() {
        List<TenantRecord> all = tenantRegistryPort.findAll();
        assertThat(all).hasSize(1);
        TenantRecord record = all.get(0);
        assertThat(record.language())
                .as("AC-PROP-LANGUAGE-OVERRIDE: runner must use property-overridden language")
                .isEqualTo("en");
    }

    /**
     * AC-PROP-LANGUAGE-OVERRIDE (DB column): per-tenant tenants table has language = "en".
     *
     * <p>DEC-41 observable-form: DB column state via JdbcTemplate.
     */
    @Test
    void firstStart_overriddenLanguageWrittenToTenantsTable() {
        JdbcTemplate jdbc = new JdbcTemplate(routingDataSource);
        String language = jdbc.queryForObject("SELECT language FROM tenants LIMIT 1", String.class);
        assertThat(language)
                .as("AC-PROP-LANGUAGE-OVERRIDE: tenants.language must reflect overridden value")
                .isEqualTo("en");
    }

    /**
     * AC-PROP-DISPLAY-NAME-OVERRIDE (DB column): per-tenant tenants table has display_name = "My
     * Club".
     *
     * <p>DEC-41 observable-form: DB column state via JdbcTemplate.
     */
    @Test
    void firstStart_overriddenDisplayNameWrittenToTenantsTable() {
        JdbcTemplate jdbc = new JdbcTemplate(routingDataSource);
        String displayName =
                jdbc.queryForObject("SELECT display_name FROM tenants LIMIT 1", String.class);
        assertThat(displayName)
                .as(
                        "AC-PROP-DISPLAY-NAME-OVERRIDE: tenants.display_name must reflect"
                                + " overridden value")
                .isEqualTo("My Club");
    }
}
