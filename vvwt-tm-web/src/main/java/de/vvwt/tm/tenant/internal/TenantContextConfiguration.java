package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.DiagnosticProperties;
import de.vvwt.tm.tenant.LocationContext;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcH2Dialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
 * <h2>RoutingTenantDataSource — activated as {@code @Primary} in E14S11</h2>
 *
 * <p>The {@link RoutingTenantDataSource} is registered as the {@code @Primary DataSource} bean
 * ({@code routingTenantDataSource}). After E14S11, all auto-wired {@link DataSource} and {@link
 * JdbcTemplate} beans route JDBC connections through per-tenant H2 files. The Spring Boot
 * auto-configured flat {@code DataSource} bean ({@code dataSource}) still exists but is no longer
 * {@code @Primary}. Components that must query the flat DataSource (e.g., {@link
 * DefaultTenantBootstrapRunner} for idempotency guarding) inject it via
 * {@code @Qualifier("dataSource")}.
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
@EnableConfigurationProperties(DataSourceProperties.class)
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
     * <p>E55S10: accepts {@link DiagnosticProperties} + {@link TmHikariProperties} for feature-
     * flagged diagnostic instrumentation and H-1 stopgap (single-writer-per-tenant).
     *
     * <p>{@link ConditionalOnMissingBean} allows test configurations to override.
     */
    @Bean
    @ConditionalOnMissingBean(TenantDataSourceResolver.class)
    public TenantDataSourceResolver tenantDataSourceResolver(
            TenantRegistryPort tenantRegistryPort,
            TmDataDirProperties dataDirProperties,
            DiagnosticProperties diagnosticProperties,
            TmHikariProperties hikariProperties) {
        return new TenantFileRegistryDataSourceResolver(
                tenantRegistryPort, dataDirProperties, diagnosticProperties, hikariProperties);
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
     * <h2>Post-Reset production semantics (E45S05 / DEC-25)</h2>
     *
     * <p>After the Wave-2 Big-Bang-Reset, per-module migration directories ({@code tenant/}, {@code
     * tournament/}, {@code auth/}, {@code certificate/}, {@code infoportal/}) are the sole schema
     * source. The Wave-1 legacy-root override (which ran root V1–V16 via {@code
     * FlywayRootMigrationsCustomizer.RootLevelOnlyResourceProvider}) is removed. The production
     * {@link PerTenantFlywayRunner} applies each module's migrations in {@code ApplicationModule}
     * dependency-tree order, each with its own history table ({@code
     * flyway_schema_history_{module}}). This is the DEC-20 + DEC-21 end-state.
     *
     * @see PerTenantFlywayRunner
     * @see <a href="../../../../../../../../docs/governance/stories/E14S04.story.md">Story
     *     E14S04</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-25.md">DEC-25</a>
     */
    @Bean
    @ConditionalOnMissingBean(PerTenantFlywayRunner.class)
    public PerTenantFlywayRunner perTenantFlywayRunner(
            TenantDataSourceResolver tenantDataSourceResolver) {
        return new PerTenantFlywayRunner(
                tenantDataSourceResolver, TournamentManagerApplication.class);
    }

    /**
     * Flat (shared) DataSource bean — the Spring Boot main-DB DataSource ({@code dataSource}).
     *
     * <h2>Why we register this explicitly (E14S11, AC2)</h2>
     *
     * <p>When {@link #routingTenantDataSource} is registered as a {@code DataSource} bean (even as
     * {@code @Primary}), Spring Boot's {@code DataSourceAutoConfiguration} detects existing {@code
     * DataSource} beans via {@code @ConditionalOnMissingBean(DataSource.class)} and SKIPS
     * auto-configuring the flat DataSource entirely. This leaves {@code @Qualifier("dataSource")}
     * injection points with no candidate bean, breaking {@link DefaultTenantBootstrapRunner} and
     * the Flyway bridge (see {@link #flywayDataSource}).
     *
     * <p>By registering the flat DataSource explicitly using {@link DataSourceProperties} (which
     * reads {@code spring.datasource.*} properties), we ensure:
     *
     * <ul>
     *   <li>The flat DataSource bean is always present as {@code "dataSource"}, regardless of
     *       auto-configuration ordering.
     *   <li>{@link DefaultTenantBootstrapRunner} can inject it via
     *       {@code @Qualifier("dataSource")}.
     *   <li>Flyway auto-configuration uses it via {@link #flywayDataSource()}.
     *   <li>Spring Boot's {@code DataSourceAutoConfiguration} gracefully skips (it sees our bean).
     * </ul>
     *
     * @param dataSourceProperties Spring Boot's {@code spring.datasource.*} configuration
     * @return the flat (non-routing) DataSource for the shared main H2 database
     * @see <a href="../../../../../../../../docs/governance/stories/E14S11.story.md">Story E14S11
     *     (AC2)</a>
     */
    @Bean("dataSource")
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    /**
     * Flat {@link TransactionTemplate} bean — backed by the flat DataSource ({@code "dataSource"}).
     *
     * <h2>Why we register this explicitly (E14S11, AC2)</h2>
     *
     * <p>Spring Boot's {@code TransactionAutoConfiguration} creates a {@code TransactionTemplate}
     * from the auto-configured {@code PlatformTransactionManager}, which in turn uses the
     * {@code @Primary} DataSource — i.e. {@link #routingTenantDataSource}. Any startup {@code
     * ApplicationRunner} that injects {@link TransactionTemplate} would then attempt to open a
     * connection via the routing DataSource before a tenant is bound, causing {@code
     * IllegalStateException: No tenant is bound}.
     *
     * <p>By registering this bean explicitly backed by the flat DataSource's transaction manager,
     * Spring Boot's auto-configuration skips creating its own
     * ({@code @ConditionalOnMissingBean(TransactionOperations.class)}). All startup runners (e.g.,
     * {@link DefaultTenantBootstrapRunner}, {@link de.vvwt.tm.auth.AdminCredentialsBootstrap}) that
     * inject {@link TransactionTemplate} receive the flat-DataSource-backed instance.
     *
     * @param flatDataSource the flat {@code "dataSource"} bean
     * @return the flat TransactionTemplate for main-DB (non-routing) transactional inserts
     * @see <a href="../../../../../../../../docs/governance/stories/E14S11.story.md">Story E14S11
     *     (AC2)</a>
     */
    @Bean
    public TransactionTemplate transactionTemplate(
            @Qualifier("dataSource") DataSource flatDataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(flatDataSource));
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
     * <h2>Flat DataSource injection (E14S11, AC2)</h2>
     *
     * <p>After {@code @Primary RoutingTenantDataSource} activation, the auto-wired {@code
     * JdbcTemplate} would route through tenant context. The bootstrap runner needs the flat
     * DataSource ({@code @Qualifier("dataSource")}) for its AC11 idempotency guard and main-DB
     * upsert — these queries run at startup time before any tenant context is bound. Using the
     * routing DataSource here would cause {@code IllegalStateException: No tenant is bound}.
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
            TenantDataSourceResolver tenantDataSourceResolver,
            TmDataDirProperties dataDirProperties,
            @Qualifier("dataSource") DataSource flatDataSource,
            TransactionTemplate transactionTemplate,
            TmBootstrapProperties bootstrapProperties) {
        JdbcTemplate flatJdbcTemplate = new JdbcTemplate(flatDataSource);
        return new DefaultTenantBootstrapRunner(
                tenantRegistryPort,
                perTenantFlywayRunner,
                tenantDataSourceResolver,
                dataDirProperties.asPath(),
                flatJdbcTemplate,
                transactionTemplate,
                bootstrapProperties);
    }

    /**
     * Spring Data JDBC {@link JdbcDialect} bean — explicitly registered as {@link JdbcH2Dialect}
     * (E14S11, AC2; updated for Spring Data JDBC 4.x in E42S01).
     *
     * <h2>Why we register this explicitly (E14S11, AC2)</h2>
     *
     * <p>Spring Boot's {@code
     * DataJdbcRepositoriesAutoConfiguration$SpringBootJdbcConfiguration.jdbcDialect} auto-detects
     * the SQL dialect by opening a JDBC connection via {@code NamedParameterJdbcOperations} (which
     * resolves to the {@code @Primary} routing DataSource). At context startup, before any tenant
     * is bound, this causes {@code IllegalStateException: No tenant is bound to the current
     * thread}.
     *
     * <p>By registering the dialect explicitly as {@link JdbcH2Dialect#INSTANCE}, Spring Boot's
     * {@code @ConditionalOnMissingBean} condition on its auto-configured {@code JdbcDialect} bean
     * is satisfied and the probe is suppressed entirely. This is correct because ALL databases in
     * the system are H2: the flat main-DB and every per-tenant DB. No dialect probe is needed.
     *
     * <h2>E42S01 migration</h2>
     *
     * <p>Migrated from {@code org.springframework.data.relational.core.dialect.H2Dialect} (which
     * only implements {@code Dialect}) to {@code
     * org.springframework.data.jdbc.core.dialect.JdbcH2Dialect} (which implements {@code
     * JdbcDialect extends Dialect}). In Spring Data JDBC 4.x, the auto-config's
     * {@code @ConditionalOnMissingBean} checks for {@code JdbcDialect} — the old {@code Dialect}
     * return type no longer satisfies the condition.
     *
     * @return the H2 JDBC dialect for Spring Data JDBC mapping context
     * @see org.springframework.data.jdbc.core.dialect.JdbcH2Dialect
     * @see <a href="../../../../../../../../docs/governance/stories/E14S11.story.md">Story E14S11
     *     (AC2)</a>
     */
    @Bean
    public JdbcDialect jdbcDialect() {
        return JdbcH2Dialect.INSTANCE;
    }

    /**
     * {@link RoutingTenantDataSource} bean — activated as {@code @Primary DataSource} in E14S11.
     *
     * <p>After this activation, every auto-wired {@link DataSource} and {@link JdbcTemplate} in the
     * Spring context resolves JDBC connections through per-tenant H2 files keyed on {@link
     * de.vvwt.tm.tenant.TenantContext#current()}. A bound {@link TenantContext} is required before
     * any JDBC call; {@link org.springframework.boot.test.context.TestConfiguration}-based
     * auto-bind (from E14S10 {@code TenantContextTestSupport}) satisfies this in all
     * {@code @SpringBootTest} integration tests.
     *
     * <h2>AC9 — Javadoc note</h2>
     *
     * <p>{@code @Primary} was added to this bean in E14S11. All consumers of the {@link DataSource}
     * now route through per-tenant context. The flat DataSource bean ({@code "dataSource"}) remains
     * available for components that must bypass routing (e.g., bootstrap runners that execute
     * before tenant context is bound).
     *
     * <h2>No @ConditionalOnMissingBean (DEC-21)</h2>
     *
     * <p>This bean MUST NOT be conditional. Per DEC-21 (no feature flags), routing activation
     * happens unconditionally. Test contexts that need a different DataSource routing strategy must
     * override via a full {@code @TestConfiguration} bean definition replacement.
     *
     * @see RoutingTenantDataSource
     * @see de.vvwt.tm.tenant.TenantContext
     * @see de.vvwt.tm.tenant.TenantDataSourceResolver
     * @see <a href="../../../../../../../../docs/governance/stories/E14S11.story.md">Story E14S11
     *     (activation)</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20
     *     (DB-per-Tenant)</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21 (no feature
     *     flags)</a>
     */
    @Bean("routingTenantDataSource")
    @Primary
    public DataSource routingTenantDataSource(
            @Qualifier("tenantRoutingContext") TenantContext tenantContext,
            TenantDataSourceResolver tenantDataSourceResolver) {
        return new RoutingTenantDataSource(tenantContext, tenantDataSourceResolver);
    }

    /**
     * Exposes the flat DataSource as {@code @FlywayDataSource} so that Spring Boot's Flyway
     * auto-configuration ({@code FlywayAutoConfiguration}) uses it instead of the {@code @Primary
     * RoutingTenantDataSource}.
     *
     * <h2>Why this is necessary (E14S11, AC2)</h2>
     *
     * <p>After {@code @Primary RoutingTenantDataSource} activation, Spring Boot's {@code
     * FlywayMigrationInitializer} would auto-wire the primary DataSource and try to open a JDBC
     * connection during context startup — BEFORE any tenant is bound. This causes {@code
     * IllegalStateException: No tenant is bound to the current thread} in the Flyway initializer.
     * The {@code @FlywayDataSource} qualifier is the Spring Boot mechanism for explicitly directing
     * Flyway to a non-primary DataSource.
     *
     * <h2>Flat DataSource semantics</h2>
     *
     * <p>The main shared H2 database (the flat DataSource) stores cross-tenant metadata (tenants
     * table, admin credentials). Per-tenant application data lives in the per-tenant H2 files
     * managed by {@link RoutingTenantDataSource}. Flyway migrations for the main-DB schema ({@code
     * db/migration}) correctly target the flat DataSource.
     *
     * @param flatDataSource the flat {@code "dataSource"} bean
     * @return the same DataSource, exposed with {@code @FlywayDataSource} qualifier
     * @see org.springframework.boot.flyway.autoconfigure.FlywayDataSource
     * @see <a href="../../../../../../../../docs/governance/stories/E14S11.story.md">Story E14S11
     *     (AC2)</a>
     */
    @Bean("flywayRoutingBridgeDataSource")
    @FlywayDataSource
    public DataSource flywayDataSource(@Qualifier("dataSource") DataSource flatDataSource) {
        return flatDataSource;
    }
}
