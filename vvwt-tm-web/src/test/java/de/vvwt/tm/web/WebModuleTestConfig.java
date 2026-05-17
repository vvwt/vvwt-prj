package de.vvwt.tm.web;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.scoring.ScoringRuleRegistry;
import de.vvwt.tm.scoring.SetValidationRuleRegistry;
import de.vvwt.tm.scoring.TournamentRuleResolver;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@code @TestConfiguration} for {@code @SpringBootTest(RANDOM_PORT)} controller ITs in the {@code
 * web} module (E22S07, DEC-38 Clause C, DEC-40 Clause A, DEC-44 D2).
 *
 * <h2>Purpose</h2>
 *
 * <p>Shared {@code @TestConfiguration} for {@code @SpringBootTest(RANDOM_PORT)} controller ITs in
 * the {@code web} module. The production {@code AuthConfiguration} (and all application beans) are
 * loaded; this config supplies test substitutes that must win over production beans via
 * {@code @Primary} or bean-definition overriding ({@code
 * spring.main.allow-bean-definition-overriding=true}).
 *
 * <h2>Auth substitution strategy (DEC-44 D2)</h2>
 *
 * <p>{@code @SpringBootTest(RANDOM_PORT)} boots the full application context including {@code
 * AuthConfiguration}. Spring Security's {@code InitializeUserDetailsManagerConfigurer} does NOT
 * respect {@code @Primary} for {@code UserDetailsService} selection — providing two {@code
 * UserDetailsService} beans causes the global {@code AuthenticationManager} to ignore both and fall
 * back to the production DB-backed one. Therefore, this config does NOT provide a {@code
 * UserDetailsService} bean. Instead, it provides:
 *
 * <ul>
 *   <li>A {@code @Primary PasswordEncoder} (distinct name {@code "webItPasswordEncoder"}) so that
 *       {@code AuthConfiguration} wires the test BCrypt encoder.
 *   <li>A non-primary {@code AdminCredentialsProvider} placeholder (distinct name {@code
 *       "webItAdminCredentialsProvider"}). Per-IT inner {@code TestAdminCredentials} classes are
 *       explicitly imported ({@code @Import({WebModuleTestConfig.class,
 *       <IT>.TestAdminCredentials.class})}) and provide a {@code @Primary} bean with the same name,
 *       which overrides this placeholder via {@code allow-bean-definition-overriding=true}. Result:
 *       exactly one {@code @Primary AdminCredentialsProvider} feeds {@code
 *       AuthConfiguration.userDetailsService()} with the per-IT hashed test password.
 * </ul>
 *
 * <p>The production {@code SecurityFilterChain} and {@code UserDetailsService} beans are the sole
 * instances, wired to the test {@code @Primary} beans above. HTTP Basic auth then accepts the
 * per-IT test password.
 *
 * <h2>Beans provided</h2>
 *
 * <ul>
 *   <li>{@link TenantContext} ({@code "tenantRoutingContext"}) — thread-local implementation that
 *       satisfies {@code tournament.internal} beans (repositories, services) that inject it.
 *   <li>{@link TenantRegistryPort} — Mockito mock returning a fixed default-tenant UUID.
 *   <li>{@link TenantContextTestSupport.Binder} — allows ITs to bind/unbind tenant context.
 *   <li>{@link TenantDataSourceResolver} — in-memory H2 resolver for test DataSources.
 *   <li>{@link PasswordEncoder} ({@code BCryptPasswordEncoder}, {@code @Primary}) — wired into
 *       production {@code AuthConfiguration} beans via {@code @Primary}.
 *   <li>{@link AdminCredentialsProvider} placeholder (non-primary) — per-IT inner {@code
 *       TestAdminCredentials} provides {@code @Primary} override via explicit {@code @Import}.
 *   <li>{@link PhotoStorageService} mock — satisfies {@code TeamController} constructor dependency.
 *   <li>{@link ScoringRuleRegistry} mock — satisfies {@code TournamentService} dependencies.
 *   <li>{@link SetValidationRuleRegistry} mock — satisfies {@code TournamentService} dependencies.
 * </ul>
 *
 * @see de.vvwt.tm.tournament.TournamentModuleTestConfig
 * @see TenantContextTestSupport
 * @see <a href="DEC-38">DEC-38 — {@code @ApplicationModuleTest} canon for reconstructed modules</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S07">E22S07 — Relocate controllers to de.vvwt.tm.web</a>
 */
@TestConfiguration
public class WebModuleTestConfig {

    /**
     * Global counter used to assign each {@code WebModuleTestConfig} bean instance a unique context
     * ID.
     *
     * <p>Each {@code @SpringBootTest} ApplicationContext that loads {@code WebModuleTestConfig}
     * creates one instance of this {@code @TestConfiguration}. The counter is static so it
     * increments monotonically across all instances in the same JVM. Each instance captures its own
     * ID via {@link #contextId}, ensuring that the H2 in-memory tenant databases created by {@link
     * #inMemoryTenantDataSourceResolver} have a unique name prefix per context.
     *
     * <p>Without this isolation, {@code DB_CLOSE_DELAY=-1} keeps named H2 in-memory databases alive
     * for the entire JVM lifetime. During {@code mvn verify}, multiple {@code @SpringBootTest}
     * contexts share the same JVM. If they use the same tenant UUID (from the stable file registry
     * at {@code tm.data.dir}), they would connect to the same H2 database ({@code web-it-{uuid}})
     * and share stale schema-history, residual test data, or trigger concurrent-migration races —
     * causing {@code PrintControllerIT}'s ApplicationContext startup to fail intermittently.
     *
     * <p>E16S04 fix — matches the isolation strategy of E16S02 ({@code generate-unique-name=true}
     * for the flat DataSource) and E16S03 ({@code ${random.uuid}} for {@code tm.data.dir}).
     */
    private static final AtomicLong CONTEXT_ID_SEQ = new AtomicLong(0);

    /**
     * Per-instance context ID, captured once at construction time from {@link #CONTEXT_ID_SEQ}.
     * Used as a unique prefix in H2 tenant database names to prevent cross-context sharing.
     */
    private final long contextId = CONTEXT_ID_SEQ.incrementAndGet();

    // =========================================================================
    // Tenant infrastructure
    // =========================================================================

    /**
     * {@link TenantContext} bean named {@code "tenantRoutingContext"} — thread-local
     * implementation.
     *
     * <p>Mirrors {@code TenantContextConfiguration.tenantRoutingContext()} from the {@code tenant}
     * module, which is excluded from this context because {@code tenant} is not a direct code-level
     * dependency of {@code web}. Provides stack-based nested bind semantics via {@link
     * ThreadLocal}.
     */
    @Bean("tenantRoutingContext")
    public TenantContext tenantRoutingContext() {
        return new ThreadLocalTenantContext();
    }

    /**
     * Exposes a {@link TenantContextTestSupport.Binder} bean within the web module's test context.
     *
     * <p>The {@link TenantRegistryPort} is provided by {@code TenantContextConfiguration} (via
     * {@code ALL_DEPENDENCIES} bootstrap mode), which creates the real {@link
     * de.vvwt.tm.tenant.internal.TenantFileRegistry}. This is required for the {@code
     * DefaultTenantBootstrapRunner} to work correctly (file-backed registry).
     *
     * @param tenantContext the {@code tenantRoutingContext} bean
     * @param tenantRegistryPort the tenant registry from TenantContextConfiguration
     * @return the Binder instance
     */
    @Bean
    public TenantContextTestSupport.Binder tenantContextBinder(
            @Qualifier("tenantRoutingContext") TenantContext tenantContext,
            TenantRegistryPort tenantRegistryPort,
            @Qualifier("routingTenantDataSource") DataSource routingDataSource) {
        return new TenantContextTestSupport.Binder(
                tenantContext, tenantRegistryPort, routingDataSource);
    }

    /**
     * In-memory {@link TenantDataSourceResolver} — replaces the production file-based resolver.
     * Each tenant UUID maps to a distinct {@code jdbc:h2:mem:web-it-{contextId}-{uuid}} database.
     *
     * <p>The {@code contextId} prefix (derived from {@link #contextId}) makes the H2 database name
     * unique to this {@code WebModuleTestConfig} instance, i.e. to the Spring test
     * ApplicationContext that loaded it. This prevents cross-context sharing of H2 databases when
     * {@code DB_CLOSE_DELAY=-1} keeps them alive across context boundaries in a single {@code mvn
     * verify} JVM run. Without this prefix, two contexts with the same tenant UUID (e.g. from the
     * stable file registry at {@code tm.data.dir}) would resolve to the same H2 database and share
     * stale schema-history — the root cause of {@code PrintControllerIT}'s intermittent
     * ApplicationContext startup failures (E16S04).
     *
     * @param dataSourceProperties the Spring Boot datasource properties for username/password
     * @return an in-memory resolver scoped to this context instance
     */
    @Bean
    public TenantDataSourceResolver inMemoryTenantDataSourceResolver(
            DataSourceProperties dataSourceProperties) {
        ConcurrentHashMap<UUID, DataSource> cache = new ConcurrentHashMap<>();
        long ctxId = contextId; // capture for lambda (effectively final)
        return tenantId ->
                cache.computeIfAbsent(
                        tenantId,
                        id -> {
                            // E16S04: include contextId prefix to isolate this context's H2
                            // databases from other Spring test contexts in the same JVM.
                            // DB_CLOSE_DELAY=-1 keeps named H2 databases alive for the JVM
                            // lifetime; without the prefix, two contexts sharing the same tenant
                            // UUID would connect to the same physical H2 instance.
                            String url =
                                    "jdbc:h2:mem:web-it-ctx"
                                            + ctxId
                                            + "-"
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

    // =========================================================================
    // Security infrastructure (substitutes for auth module, not loaded by
    // DIRECT_DEPENDENCIES — web allowedDependencies does not include auth)
    // =========================================================================

    /**
     * BCrypt password encoder. Mirrors {@code AuthConfiguration#passwordEncoder()} for the IT
     * context. Required by each IT's inner {@code TestAdminCredentials} to hash test passwords.
     *
     * <p>{@code @Primary} + explicit bean name added per DEC-44 D2:
     * {@code @SpringBootTest(RANDOM_PORT)} boots the full application context including {@code
     * AuthConfiguration}, which registers a {@code PasswordEncoder} bean named {@code
     * "passwordEncoder"}. By using a distinct name ({@code "webItPasswordEncoder"}) +
     * {@code @Primary}, this test substitute coexists with the production bean and is preferred for
     * autowiring without triggering bean-definition overriding.
     */
    @Bean("webItPasswordEncoder")
    @Primary
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Placeholder {@link AdminCredentialsProvider} — returns an empty hash string. Each controller
     * IT overrides this with a {@code @Primary} test-specific provider via its inner {@code
     * TestAdminCredentials} class, which is explicitly imported via
     * {@code @Import({WebModuleTestConfig.class, <IT>.TestAdminCredentials.class})}.
     *
     * <p>This placeholder is non-primary. The per-IT {@code testAdminCredentialsProvider} bean
     * carries {@code @Primary}; the production {@code adminCredentialsProvider} from {@code
     * AuthConfiguration} is also non-primary. With exactly one {@code @Primary
     * AdminCredentialsProvider} in the context, {@code AuthConfiguration.userDetailsService()}
     * resolves it unambiguously.
     *
     * <p>Distinct bean name {@code "webItAdminCredentialsProvider"} avoids overriding the
     * production {@code adminCredentialsProvider} bean from {@code AuthConfiguration} per DEC-44
     * D2.
     */
    @Bean("webItAdminCredentialsProvider")
    public AdminCredentialsProvider adminCredentialsProvider() {
        return () -> "";
    }

    // =========================================================================
    // Domain-layer mocks (domain.* is not a Modulith module; not loaded by
    // DIRECT_DEPENDENCIES)
    // =========================================================================

    /**
     * Mockito mock for {@link PhotoStorageService} — kept for legacy compatibility with slice
     * tests.
     *
     * <p>Post-E23S05 Cutover-1: the production {@code defaultPhotoStorageService} bean (annotated
     * {@code @Primary}) wins for autowiring; this non-primary mock bean coexists in the context but
     * is not injected into production beans. {@code TeamPhotoControllerIT} uses the real
     * implementation; other ITs that list teams will get {@code hasPhoto=false} from the real bean
     * (filesystem lookup with no files present — safe for non-photo ITs).
     */
    @Bean
    public PhotoStorageService photoStorageService() {
        return Mockito.mock(PhotoStorageService.class);
    }

    /**
     * Mockito mock for {@link ScoringRuleRegistry} — satisfies {@code TournamentService} and {@code
     * TournamentRulesController} constructor injection.
     *
     * <p>{@code @Primary} is required post-E22S11: the production {@code
     * scoringModuleScoringRuleRegistry} bean is always on the classpath once the scoring module is
     * loaded (no {@code @ComponentScan} exclusions). {@code @Primary} ensures the mock wins over
     * the production bean for both {@code TournamentService} and the transitively-wired {@code
     * TournamentRuleResolver}.
     */
    @Bean
    @Primary
    public ScoringRuleRegistry scoringRuleRegistry() {
        return Mockito.mock(ScoringRuleRegistry.class);
    }

    /**
     * Mockito mock for {@link SetValidationRuleRegistry} — satisfies {@code TournamentService} and
     * {@code TournamentRulesController} constructor injection.
     *
     * <p>{@code @Primary} is required post-E22S11: the production {@code
     * scoringModuleSetValidationRuleRegistry} bean is always on the classpath once the scoring
     * module is loaded (no {@code @ComponentScan} exclusions). {@code @Primary} ensures the mock
     * wins.
     */
    @Bean
    @Primary
    public SetValidationRuleRegistry setValidationRuleRegistry() {
        return Mockito.mock(SetValidationRuleRegistry.class);
    }

    /**
     * Mockito mock for {@link TournamentRuleResolver} — satisfies {@code DefaultScoringService}
     * constructor injection when the {@code scoring} module is loaded transitively via
     * {@code @ApplicationModuleTest(ALL_DEPENDENCIES)}.
     *
     * <p>{@code scoring.internal.TournamentRuleResolver} is a {@code @Component} in the {@code
     * de.vvwt.tm.scoring.internal} package (scoring module internal). This mock provides the bean
     * explicitly so that {@code DefaultScoringService} can be wired in
     * {@code @ApplicationModuleTest} contexts that include the scoring module transitively.
     *
     * <p>Added in E22S09: once {@code de.vvwt.tm.web.ScoreApiController} imports {@code
     * ScoreEntryService}, Spring Modulith detects a real bytecode dependency on {@code scoring} and
     * includes it in the test context — which transitively requires this bean.
     *
     * <p>{@code @Primary} is required post-E22S11: after the atomic cutover, the production {@code
     * scoring.internal.TournamentRuleResolver} ({@code scoringTournamentRuleResolver}) is always on
     * the classpath with no {@code @ComponentScan} exclusions suppressing it. Without
     * {@code @Primary}, Spring finds two candidates ({@code scoringTournamentRuleResolver} + this
     * mock) and throws {@code UnsatisfiedDependencyException}. The mock must win for all web-module
     * ITs that need the scoring module wired transitively.
     */
    @Bean
    @Primary
    public TournamentRuleResolver tournamentRuleResolver() {
        return Mockito.mock(TournamentRuleResolver.class);
    }

    // =========================================================================
    // Internal TenantContext implementation
    // =========================================================================

    /**
     * Thread-local-backed {@link TenantContext} implementation for tests.
     *
     * <p>Mirrors {@code de.vvwt.tm.tenant.internal.ThreadLocalTenantContextImpl} but declared here
     * to avoid importing from {@code tenant.internal}. Provides stack-based nested bind semantics.
     */
    static final class ThreadLocalTenantContext implements TenantContext {

        private final ThreadLocal<Deque<UUID>> stack = ThreadLocal.withInitial(ArrayDeque::new);

        @Override
        public UUID current() {
            UUID id = stack.get().peek();
            if (id == null) {
                throw new IllegalStateException("No tenant bound to current thread");
            }
            return id;
        }

        @Override
        public Scope bind(UUID tenantId) {
            if (tenantId == null) {
                throw new IllegalArgumentException("tenantId must not be null");
            }
            stack.get().push(tenantId);
            return () -> stack.get().poll();
        }
    }
}
