package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.LocationContext;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring configuration for the {@code tenant} bounded context (post-E14S07 atomic cutover).
 *
 * <h2>Beans registered</h2>
 *
 * <ul>
 *   <li>{@link ThreadLocalTenantContextImpl} as {@link TenantContext} (singleton)
 *   <li>{@link TenantFileRegistry} as {@link TenantRegistryPort} (singleton, backed by {@link
 *       TmDataDirProperties})
 *   <li>{@link TenantFileRegistryDataSourceResolver} as {@link TenantDataSourceResolver}
 *       (singleton, backed by {@link TenantFileRegistry})
 *   <li>{@link ThreadLocalLocationContextImpl} as {@link de.vvwt.tm.tenant.LocationContext}
 *       (E14S09)
 *   <li>{@link PerTenantFlywayRunner} (E14S04)
 *   <li>{@link DefaultTenantBootstrapRunner} (E14S05)
 * </ul>
 *
 * <h2>RoutingTenantDataSource (deferred to E14S11)</h2>
 *
 * <p>The {@link RoutingTenantDataSource} is NOT yet registered as {@code @Primary DataSource}.
 * Activation lands in E14S11 after the E14S10 test-infrastructure auto-bind story. The flat Spring
 * Boot auto-configured DataSource remains in use post-cutover.
 *
 * <h2>Internal placement (AC6 / DEC-21)</h2>
 *
 * <p>This class lives in {@code de.vvwt.tm.tenant.internal} and MUST NOT be imported by any class
 * outside the {@code tenant} module.
 *
 * @see ThreadLocalTenantContextImpl
 * @see RoutingTenantDataSource
 * @see TenantFileRegistry
 * @see <a href="../../../../../../../../docs/governance/stories/E14S03.story.md">Story E14S03</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22</a>
 */
@Configuration
public class TenantContextConfiguration {

    /**
     * {@link TenantContext} bean — provides per-thread tenant binding.
     *
     * <p>The bean is named {@code tenantRoutingContext} (not {@code tenantContext}) to avoid
     * collision with the {@code de.vvwt.tm.domain.repo.TenantContext} bean, which also uses the
     * default bean name {@code tenantContext} from its {@code @Component} annotation. The name
     * collision would cause Spring to override the domain-layer bean with this factory-method bean,
     * breaking the existing repository layer that depends on the legacy type. Both beans coexist
     * until the E15 auth cutover reconstructs the repository layer.
     *
     * <p>The {@link ConditionalOnMissingBean} guard (typed, not by name) allows test configurations
     * to supply a test-specific {@link TenantContext} implementation.
     */
    @Bean("tenantRoutingContext")
    @ConditionalOnMissingBean(TenantContext.class)
    public TenantContext tenantRoutingContext() {
        return new ThreadLocalTenantContextImpl();
    }

    /**
     * {@link LocationContext} bean — provides per-thread location binding (E14S09, DEC-24 D2).
     *
     * <p>Backed by {@link ThreadLocalLocationContextImpl} (stack-based, mirrors TenantContext).
     * Lives in {@code tenant::api} public surface per DEC-24: location context is resolved at
     * WebSocket handshake time from the device's {@code location_id} field.
     *
     * <p>{@link ConditionalOnMissingBean} allows test configurations to supply a test-specific
     * {@link LocationContext} implementation.
     *
     * @see ThreadLocalLocationContextImpl
     * @see de.vvwt.tm.tenant.LocationContext
     * @see <a href="../../../../../../../../docs/governance/stories/E14S09.story.md">Story
     *     E14S09</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-24.md">DEC-24</a>
     */
    @Bean
    @ConditionalOnMissingBean(LocationContext.class)
    public LocationContext locationContext() {
        return new ThreadLocalLocationContextImpl();
    }

    /**
     * {@link TenantRegistryPort} bean — file-backed JSON registry of tenants.
     *
     * <p>Uses {@link TmDataDirProperties} for the data directory root. {@link
     * ConditionalOnMissingBean} allows test configurations to override.
     */
    @Bean
    @ConditionalOnMissingBean(TenantRegistryPort.class)
    public TenantRegistryPort tenantRegistryPort(TmDataDirProperties dataDirProperties) {
        return new TenantFileRegistry(dataDirProperties.asPath());
    }

    /**
     * {@link TenantDataSourceResolver} bean — resolves per-tenant H2 DataSources.
     *
     * <p>Backed by {@link TenantFileRegistryDataSourceResolver}, which combines the {@link
     * TenantRegistryPort} (tenant existence) with {@link TenantDirectoryHelper} (H2 file path
     * computation) to create and cache per-tenant DataSources.
     *
     * <p>{@link ConditionalOnMissingBean} allows test configurations to override.
     */
    @Bean
    @ConditionalOnMissingBean(TenantDataSourceResolver.class)
    public TenantDataSourceResolver tenantDataSourceResolver(
            TenantRegistryPort tenantRegistryPort, TmDataDirProperties dataDirProperties) {
        return new TenantFileRegistryDataSourceResolver(tenantRegistryPort, dataDirProperties);
    }

    /**
     * {@link PerTenantFlywayRunner} bean — runs per-module Flyway migrations for a single tenant.
     *
     * <p>Consumes {@link TenantDataSourceResolver} and uses {@code
     * ApplicationModules.of(TournamentManagerApplication.class)} internally to derive
     * module-dependency-ordered migration locations (DEC-21). Invoked by E14S05 (default-tenant
     * bootstrap) and future Wave-2 tenant lifecycle operations.
     *
     * <p>{@link ConditionalOnMissingBean} allows test configurations to override (e.g., for testing
     * E14S05 without running real Flyway migrations in the test context).
     *
     * @see PerTenantFlywayRunner
     * @see <a href="../../../../../../../../docs/governance/stories/E14S04.story.md">Story
     *     E14S04</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
     */
    @Bean
    @ConditionalOnMissingBean(PerTenantFlywayRunner.class)
    public PerTenantFlywayRunner perTenantFlywayRunner(
            TenantDataSourceResolver tenantDataSourceResolver) {
        return new PerTenantFlywayRunner(
                tenantDataSourceResolver, TournamentManagerApplication.class);
    }

    /**
     * {@link DefaultTenantBootstrapRunner} bean — bootstraps the default tenant on first start.
     *
     * <p>Runs as an {@link ApplicationRunner} at {@code @Order(1)} (sole bootstrap runner after
     * E14S07 atomic cutover per DEC-21). Uses {@link TenantRegistryPort}, {@link
     * PerTenantFlywayRunner}, and the data directory to: detect orphans, check for prior
     * registration, create the default tenant's H2 file, run per-tenant Flyway migrations, and
     * register the tenant in the registry.
     *
     * <p>{@link ConditionalOnMissingBean} allows test configurations to supply a no-op {@link
     * ApplicationRunner} instead (avoids real Flyway runs in Spring context tests that use the
     * default {@code application-test.yml} with an in-memory DataSource).
     *
     * @see DefaultTenantBootstrapRunner
     * @see <a href="../../../../../../../../docs/governance/stories/E14S05.story.md">Story
     *     E14S05</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-17.md">DEC-17</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
     */
    @Bean
    @ConditionalOnMissingBean(DefaultTenantBootstrapRunner.class)
    public DefaultTenantBootstrapRunner defaultTenantBootstrapRunner(
            TenantRegistryPort tenantRegistryPort,
            PerTenantFlywayRunner perTenantFlywayRunner,
            TmDataDirProperties dataDirProperties,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        return new DefaultTenantBootstrapRunner(
                tenantRegistryPort,
                perTenantFlywayRunner,
                dataDirProperties.asPath(),
                jdbcTemplate,
                transactionTemplate);
    }
}
