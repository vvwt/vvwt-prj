package de.vvwt.tm.tenant.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the {@code tenant} bounded context.
 *
 * <h2>Beans registered (parallel-development phase)</h2>
 * <ul>
 *   <li>{@link ThreadLocalTenantContextImpl} as {@link TenantContext} (singleton)</li>
 *   <li>{@link TenantFileRegistry} as {@link TenantRegistryPort} (singleton, backed by
 *       {@link TmDataDirProperties})</li>
 *   <li>{@link TenantFileRegistryDataSourceResolver} as {@link TenantDataSourceResolver}
 *       (singleton, backed by {@link TenantFileRegistry})</li>
 * </ul>
 *
 * <h2>Reconstruction-in-place (DEC-21) — RoutingTenantDataSource wiring deferred to E14S07</h2>
 * <p>Following the atomic cutover protocol (DEC-21), the {@link RoutingTenantDataSource} is
 * NOT registered as a Spring bean here. Registering it as a {@link javax.sql.DataSource}
 * subtype during the parallel-development phase would suppress Spring Boot's DataSource
 * auto-configuration (which uses {@code @ConditionalOnMissingBean(DataSource.class)}), breaking
 * the existing Flyway + JPA infrastructure that the rest of the codebase depends on.
 *
 * <p>The {@link RoutingTenantDataSource} exists as a tested, production-ready class.
 * The atomic cutover in E14S07 will:
 * <ol>
 *   <li>Add {@code @Primary DataSource} bean registration here (replacing the auto-config).</li>
 *   <li>Remove the legacy DataSource auto-configuration override.</li>
 *   <li>Migrate Flyway to per-tenant runners (E14S04).</li>
 * </ol>
 *
 * <h2>No new EntityManagerFactory (AC8 / DEC-20)</h2>
 * <p>No new {@code EntityManagerFactory} is introduced at this stage or at E14S07 cutover.
 * All JPA metadata remains shared. The routing DataSource will be wired into the existing
 * {@code EntityManagerFactory} chain at cutover — without adding a second EMF.
 *
 * <h2>Internal placement (AC6 / DEC-21)</h2>
 * <p>This class lives in {@code de.vvwt.tm.tenant.internal} and MUST NOT be imported by any
 * class outside the {@code tenant} module.
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
     * collision with the legacy {@code de.vvwt.tm.domain.repo.TenantContext} bean, which also
     * uses the default bean name {@code tenantContext} from its {@code @Component} annotation.
     * The name collision would cause Spring to override the legacy bean with this factory-method
     * bean, breaking the existing repository layer that depends on the legacy type. Both beans
     * coexist until the E14S07 atomic cutover removes the legacy class.
     *
     * <p>The {@link ConditionalOnMissingBean} guard (typed, not by name) allows test
     * configurations to supply a test-specific {@link TenantContext} implementation.
     */
    @Bean("tenantRoutingContext")
    @ConditionalOnMissingBean(TenantContext.class)
    public TenantContext tenantRoutingContext() {
        return new ThreadLocalTenantContextImpl();
    }

    /**
     * {@link TenantRegistryPort} bean — file-backed JSON registry of tenants.
     *
     * <p>Uses {@link TmDataDirProperties} for the data directory root.
     * {@link ConditionalOnMissingBean} allows test configurations to override.
     */
    @Bean
    @ConditionalOnMissingBean(TenantRegistryPort.class)
    public TenantRegistryPort tenantRegistryPort(TmDataDirProperties dataDirProperties) {
        return new TenantFileRegistry(dataDirProperties.asPath());
    }

    /**
     * {@link TenantDataSourceResolver} bean — resolves per-tenant H2 DataSources.
     *
     * <p>Backed by {@link TenantFileRegistryDataSourceResolver}, which combines
     * the {@link TenantRegistryPort} (tenant existence) with {@link TenantDirectoryHelper}
     * (H2 file path computation) to create and cache per-tenant DataSources.
     *
     * <p>{@link ConditionalOnMissingBean} allows test configurations to override.
     */
    @Bean
    @ConditionalOnMissingBean(TenantDataSourceResolver.class)
    public TenantDataSourceResolver tenantDataSourceResolver(
            TenantRegistryPort tenantRegistryPort,
            TmDataDirProperties dataDirProperties) {
        return new TenantFileRegistryDataSourceResolver(tenantRegistryPort, dataDirProperties);
    }
}
