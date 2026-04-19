package de.vvwt.tm.tenant;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared {@code @TestConfiguration} that provides auto-bind infrastructure for the default-tenant
 * {@link TenantContext} in {@code @SpringBootTest} integration tests.
 *
 * <h2>Purpose</h2>
 *
 * <p>When {@link org.springframework.context.annotation.Import @Import}ed by a
 * {@code @SpringBootTest} test class, this configuration exposes a {@link Binder} bean that
 * integration tests use in {@code @BeforeEach} / {@code @AfterEach} to bind and unbind the
 * default-tenant {@link TenantContext} via the new {@code tenant::api} — i.e. {@link
 * TenantContext#bind(UUID)} (stack-based, try-with-resources safe).
 *
 * <h2>Design rationale</h2>
 *
 * <p>The implementation uses the new {@code tenant::api} {@link TenantContext} (qualified as {@code
 * tenantRoutingContext}) rather than the legacy {@code de.vvwt.tm.domain.repo.TenantContext}
 * bridge. This pre-positions test infrastructure for the E14S11 RoutingDataSource activation, where
 * the routing resolves tenant via {@link TenantContext#current()} on the new context bean.
 *
 * <h2>Governance</h2>
 *
 * <ul>
 *   <li>This class is annotated with {@link TestConfiguration} (NOT {@code @Configuration}) so the
 *       production {@link org.springframework.boot.autoconfigure.SpringBootApplication} scan does
 *       NOT pick it up in non-test code paths (AC2).
 *   <li>It only imports from {@code de.vvwt.tm.tenant} (the public API) — never from {@code
 *       de.vvwt.tm.tenant.internal} (AC8).
 *   <li>No {@code @Conditional*} or {@code @Profile} annotations are added to any production bean
 *       (AC7).
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @SpringBootTest(...)
 * @ActiveProfiles("test")
 * @Import(TenantContextTestSupport.class)
 * class MyIT {
 *
 *     @Autowired
 *     private TenantContextTestSupport.Binder tenantBinder;
 *
 *     @BeforeEach
 *     void bindTenant() { tenantBinder.bindDefaultTenant(); }
 *
 *     @AfterEach
 *     void clearTenant() { tenantBinder.unbind(); }
 * }
 * }</pre>
 *
 * @see Binder
 * @see TenantContext
 * @see TenantRegistryPort
 * @see <a href="../../../../../../../../docs/governance/stories/E14S10.story.md">Story E14S10</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20
 *     (DB-per-Tenant)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-21.md">DEC-21 (Modulith —
 *     no @Conditional)</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-22.md">DEC-22 (TDD)</a>
 */
@TestConfiguration
public class TenantContextTestSupport {

    /**
     * {@link Binder} bean — exposed to test classes that {@code @Import} this configuration.
     *
     * <p>Requires the {@code tenantRoutingContext} bean (the new {@code tenant::api} {@link
     * TenantContext} implementation) and the {@link TenantRegistryPort} to resolve the
     * default-tenant UUID from the E14S05 bootstrap. Both beans are present in any
     * {@code @SpringBootTest} context that loads {@code TournamentManagerApplication.class}.
     *
     * @param tenantContext the new {@link TenantContext} implementation bean (named {@code
     *     tenantRoutingContext} to avoid collision with the legacy {@code
     *     de.vvwt.tm.domain.repo.TenantContext} bean — see E14S03)
     * @param tenantRegistryPort the tenant registry for resolving the default tenant UUID
     * @return a {@link Binder} instance wired with both dependencies
     */
    @Bean
    public Binder tenantContextBinder(
            @Qualifier("tenantRoutingContext") TenantContext tenantContext,
            TenantRegistryPort tenantRegistryPort) {
        return new Binder(tenantContext, tenantRegistryPort);
    }

    /**
     * In-memory {@link TenantDataSourceResolver} for integration tests (E14S11).
     *
     * <p>Replaces the production {@link
     * de.vvwt.tm.tenant.internal.TenantFileRegistryDataSourceResolver} in {@code @SpringBootTest}
     * contexts that {@code @Import} this configuration. Each tenant UUID gets its own isolated
     * in-memory H2 database — avoiding file-based H2 sharing across Spring test contexts (which
     * causes {@code UQ_TEAM_AVATAR} / {@code FK_ACTIVITY_TYPES} violations).
     *
     * <p>The {@link ConditionalOnMissingBean} guard in {@link
     * de.vvwt.tm.tenant.internal.TenantContextConfiguration#tenantDataSourceResolver} defers to
     * this bean when present, per DEC-21 (no {@code @Profile} or {@code @Conditional} added to
     * production code).
     *
     * <p>DataSources are cached per UUID and reused within the same Spring context (idempotent).
     * Each in-memory H2 URL is unique per tenant so cross-tenant isolation still holds in
     * multi-tenant test scenarios (e.g., {@link de.vvwt.tm.tenant.RoutingDataSourceActivationIT}).
     */
    @Bean
    public TenantDataSourceResolver inMemoryTenantDataSourceResolver(
            DataSourceProperties dataSourceProperties) {
        return new InMemoryTenantDataSourceResolver(dataSourceProperties);
    }

    /**
     * In-memory {@link TenantDataSourceResolver} implementation.
     *
     * <p>Each tenant UUID maps to a distinct {@code jdbc:h2:mem:{uuid}} database. Schema is applied
     * by the production {@link de.vvwt.tm.tenant.internal.PerTenantFlywayRunner} (same as
     * production — no test-only Flyway config).
     */
    public static final class InMemoryTenantDataSourceResolver implements TenantDataSourceResolver {

        private final ConcurrentHashMap<UUID, DataSource> cache = new ConcurrentHashMap<>();
        private final DataSourceProperties dataSourceProperties;

        public InMemoryTenantDataSourceResolver(DataSourceProperties dataSourceProperties) {
            this.dataSourceProperties = dataSourceProperties;
        }

        @Override
        public DataSource resolve(UUID tenantId) {
            return cache.computeIfAbsent(
                    tenantId,
                    id -> {
                        String url =
                                "jdbc:h2:mem:tenant-"
                                        + id
                                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE";
                        return DataSourceBuilder.create()
                                .url(url)
                                .username(dataSourceProperties.determineUsername())
                                .password(dataSourceProperties.determinePassword())
                                .driverClassName("org.h2.Driver")
                                .build();
                    });
        }
    }

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------

    /**
     * Helper for binding and unbinding the default-tenant {@link TenantContext} in test methods.
     *
     * <h2>Lifecycle</h2>
     *
     * <p>Call {@link #bindDefaultTenant()} in {@code @BeforeEach}; call {@link #unbind()} in
     * {@code @AfterEach}. The bind is stack-based ({@link TenantContext#bind(UUID)}) so nested
     * binds within the test method are fully supported (AC-NESTED-BIND from E14S01/AC3).
     *
     * <h2>Thread safety</h2>
     *
     * <p>The underlying {@link TenantContext} is {@code ThreadLocal}-backed (one stack per thread).
     * {@link Binder} instances are shared across test methods by Spring injection, but each test
     * method operates on its own thread — so there is no cross-test state leakage as long as {@link
     * #unbind()} is called in {@code @AfterEach}.
     */
    public static final class Binder {

        private final TenantContext tenantContext;
        private final TenantRegistryPort tenantRegistryPort;

        /**
         * Stack of active scopes held between paired {@link #bindDefaultTenant()} and {@link
         * #unbind()} calls.
         *
         * <p>A {@code Deque} (LIFO) is used instead of a single field to support safe
         * double-binding: when {@code @Transactional} test methods require a tenant before the
         * transaction starts (via {@code @BeforeTransaction}) AND the standard {@code @BeforeEach}
         * also calls {@link #bindDefaultTenant()}, both calls push onto the stack. Each paired
         * {@link #unbind()} call pops the most-recent scope. This preserves the stack-based
         * semantics of {@link TenantContext#bind(UUID)} and avoids scope leaks when both lifecycle
         * hooks are used.
         */
        private final Deque<TenantContext.Scope> scopeStack = new ArrayDeque<>();

        /**
         * Constructs a {@link Binder} with the required dependencies.
         *
         * @param tenantContext the {@code tenant::api} {@link TenantContext} implementation
         * @param tenantRegistryPort the registry used to resolve the default-tenant UUID
         */
        public Binder(TenantContext tenantContext, TenantRegistryPort tenantRegistryPort) {
            if (tenantContext == null) {
                throw new IllegalArgumentException("tenantContext must not be null");
            }
            if (tenantRegistryPort == null) {
                throw new IllegalArgumentException("tenantRegistryPort must not be null");
            }
            this.tenantContext = tenantContext;
            this.tenantRegistryPort = tenantRegistryPort;
        }

        /**
         * Binds the default-tenant UUID to the current thread via {@link TenantContext#bind(UUID)}.
         *
         * <p>The binding is stack-based — multiple calls (e.g., nested scenarios in a test) push
         * additional entries onto the stack; each paired {@link #unbind()} call pops one entry.
         *
         * <p>Tests that need the default-tenant UUID for domain object construction can retrieve it
         * from the return value.
         *
         * @return the default-tenant UUID that was bound (for use in domain object construction
         *     within the test)
         * @throws IllegalStateException if the registry has no registered tenants (should not occur
         *     in a properly bootstrapped {@code @SpringBootTest} context)
         */
        public UUID bindDefaultTenant() {
            UUID defaultTenantId = tenantRegistryPort.getDefault();
            TenantContext.Scope scope = tenantContext.bind(defaultTenantId);
            scopeStack.push(scope);
            return defaultTenantId;
        }

        /**
         * Releases the most recently established tenant binding.
         *
         * <p>Closes the {@link TenantContext.Scope} returned by the last {@link
         * #bindDefaultTenant()} call. Safe to call even if {@link #bindDefaultTenant()} was not
         * called (idempotent — does nothing if no scope is active).
         *
         * <p>Intended for use in {@code @AfterEach}. If the test method threw an exception before
         * binding, this is a no-op.
         */
        public void unbind() {
            TenantContext.Scope scope = scopeStack.poll();
            if (scope != null) {
                scope.close();
            }
        }

        /**
         * Returns the underlying {@code tenant::api} {@link TenantContext} for use in test methods
         * that need to perform nested or mid-test tenant switching via {@link
         * TenantContext#bind(UUID)}.
         *
         * <p>Usage example (mid-test tenant switch):
         *
         * <pre>{@code
         * try (TenantContext.Scope scope = tenantContextBinder.tenantContext().bind(otherTenantId)) {
         *     // queries inside this block see otherTenantId
         * }
         * // after scope closes, outer default-tenant binding is restored
         * }</pre>
         *
         * @return the {@link TenantContext} instance (never {@code null})
         */
        public TenantContext tenantContext() {
            return tenantContext;
        }
    }
}
