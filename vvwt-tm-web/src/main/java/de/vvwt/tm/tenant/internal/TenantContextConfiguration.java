package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.TournamentManagerApplication;
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
     * <h2>Wave-1 legacy-root fallback (E14S11)</h2>
     *
     * <p>In Wave-1, no per-module migration directories exist yet (they are added by E15 stories).
     * {@link PerTenantFlywayRunner#buildLocations()} therefore returns an empty list, which would
     * leave every per-tenant H2 database with no schema — causing repository calls routed through
     * {@link RoutingTenantDataSource} to fail with "table not found" after E14S11 activation.
     *
     * <p>This bean overrides {@code buildLocations()} to fall back to {@code
     * classpath:db/migration} (the legacy root) when the per-module scan returns nothing. This
     * ensures that the per-tenant DB receives the same schema as the flat main-DB in Wave-1. In
     * Wave-2 (E15+), when per-module directories are added, the override no longer activates (the
     * per-module scan returns non-empty), and the production {@code PerTenantFlywayRunner}
     * semantics are restored automatically.
     *
     * @see PerTenantFlywayRunner
     * @see <a href="../../../../../../../../docs/governance/stories/E14S04.story.md">Story
     *     E14S04</a>
     * @see <a href="../../../../../../../../docs/governance/stories/E14S11.story.md">Story E14S11
     *     (Wave-1 fallback)</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
     * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21</a>
     */
    @Bean
    @ConditionalOnMissingBean(PerTenantFlywayRunner.class)
    public PerTenantFlywayRunner perTenantFlywayRunner(
            TenantDataSourceResolver tenantDataSourceResolver) {
        return new PerTenantFlywayRunner(
                tenantDataSourceResolver, TournamentManagerApplication.class) {
            /**
             * Wave-1 override: runs root domain migrations (V1–V16) first, then runs each
             * module-specific location in a separate Flyway instance with its own schema-history
             * table.
             *
             * <p>In Wave-1, the module-specific directories (e.g. {@code auth/}) use version
             * numbers that overlap with the root (both have V1). Running them in the same Flyway
             * instance causes a "Found more than one migration with version 1" error. The solution
             * is to mirror what Spring Modulith's {@code FlywayMigrationStrategy} does: each module
             * directory uses its own Flyway instance with its own schema-history table ({@code
             * flyway_schema_history_{module}}).
             *
             * <p>In Wave-2 (E15+), migrations will be relocated to per-module directories with
             * non-conflicting version numbers and the standard {@link PerTenantFlywayRunner}
             * behavior will be used.
             */
            @Override
            public void runWithDataSource(
                    java.util.UUID tenantId, javax.sql.DataSource dataSource) {
                // Wave-1: apply root domain migrations (V1–V16) and per-module migrations in
                // SEPARATE Flyway instances, each with its own schema-history table.
                //
                // Root (V1–V16) and the auth module both use version 1. Running them in a single
                // Flyway instance causes "Found more than one migration with version 1".
                //
                // The production FlywayRootMigrationsCustomizer (used by Spring Boot auto-config)
                // solves this by supplying a custom ResourceProvider that returns only root-level
                // files. We apply the same pattern here for the per-tenant Flyway instance.

                // Step 1: root domain migrations (V1–V16) using the standard
                // flyway_schema_history table. The per-tenant DB is a separate H2 file — it
                // has its own history table, independent of the flat main-DB's history.
                // We use RootLevelOnlyResourceProvider (from FlywayRootMigrationsCustomizer)
                // to exclude auth/V1 from the root scan, exactly as Spring Boot auto-config does
                // for the flat DB.
                org.flywaydb.core.Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .resourceProvider(
                                new de.vvwt.tm.infrastructure.FlywayRootMigrationsCustomizer
                                        .RootLevelOnlyResourceProvider())
                        .load()
                        .migrate();

                // Step 2: per-module migrations, each with its own history table.
                // baselineOnMigrate(true) + baselineVersion("0") handles the case where the
                // per-tenant DB already has tables from Step 1 but the module-specific history
                // table does not yet exist. Flyway would otherwise throw "non-empty schema but
                // no schema history table". With baseline-on-migrate, Flyway creates the history
                // table and baselines at version 0 (before V1), then applies the module's
                // pending migrations. On subsequent runs the history table already exists and
                // baselineOnMigrate is a no-op.
                java.util.List<String> moduleLocations = super.buildLocations();
                for (String location : moduleLocations) {
                    String moduleName = location.substring(location.lastIndexOf('/') + 1);
                    String historyTable = "flyway_schema_history_" + moduleName;
                    org.flywaydb.core.Flyway.configure()
                            .dataSource(dataSource)
                            .locations(location)
                            .table(historyTable)
                            .baselineOnMigrate(true)
                            .baselineVersion("0")
                            .load()
                            .migrate();
                }
            }
        };
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
            TransactionTemplate transactionTemplate) {
        JdbcTemplate flatJdbcTemplate = new JdbcTemplate(flatDataSource);
        return new DefaultTenantBootstrapRunner(
                tenantRegistryPort,
                perTenantFlywayRunner,
                tenantDataSourceResolver,
                dataDirProperties.asPath(),
                flatJdbcTemplate,
                transactionTemplate);
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
