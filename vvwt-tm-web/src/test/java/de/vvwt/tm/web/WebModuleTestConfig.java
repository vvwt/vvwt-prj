package de.vvwt.tm.web;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.domain.photo.PhotoStorageService;
import de.vvwt.tm.domain.rules.ScoringRuleRegistry;
import de.vvwt.tm.domain.rules.SetValidationRuleRegistry;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * {@code @TestConfiguration} for {@code @ApplicationModuleTest} controller ITs in the {@code web}
 * module (E22S07, DEC-38 Clause C, DEC-40 Clause A).
 *
 * <h2>Purpose</h2>
 *
 * <p>Mirrors {@link de.vvwt.tm.tournament.TournamentModuleTestConfig} but resides in the {@code
 * de.vvwt.tm.web} package so that Spring Modulith 1.4.6's {@code
 * ModuleTestExecutionBeanDefinitionSelector} classifies it correctly for
 * {@code @ApplicationModuleTest(web)} contexts.
 *
 * <h2>Why TenantContext is provided here</h2>
 *
 * <p>Web module ITs use {@code ALL_DEPENDENCIES} bootstrap mode so that {@code tenant.internal}
 * beans (including {@code TenantContextConfiguration} and {@code TenantContextResolver}) are
 * loaded. However, {@code TenantContextConfiguration.tenantRoutingContext()} has
 * {@code @ConditionalOnMissingBean(TenantContext.class)} — this config provides the bean first so
 * that the production bean is suppressed and the test-local thread-local implementation is used.
 * Similarly, {@link TenantRegistryPort} and {@link TenantDataSourceResolver} are overridden here.
 *
 * <h2>Beans provided</h2>
 *
 * <ul>
 *   <li>{@link TenantContext} ({@code "tenantRoutingContext"}) — thread-local implementation that
 *       satisfies {@code tournament.internal} beans (repositories, services) that inject it.
 *   <li>{@link TenantRegistryPort} — Mockito mock returning a fixed default-tenant UUID.
 *   <li>{@link TenantContextTestSupport.Binder} — allows ITs to bind/unbind tenant context.
 *   <li>{@link TenantDataSourceResolver} — in-memory H2 resolver for test DataSources.
 *   <li>{@link PasswordEncoder} ({@code BCryptPasswordEncoder}) — required for auth.
 *   <li>{@link AdminCredentialsProvider} (placeholder) — overridden by each IT's inner class.
 *   <li>{@link UserDetailsService} — backed by the (overridable) {@link AdminCredentialsProvider}.
 *   <li>{@link SecurityFilterChain} — production-equivalent authorization rules.
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
            TenantRegistryPort tenantRegistryPort) {
        return new TenantContextTestSupport.Binder(tenantContext, tenantRegistryPort);
    }

    /**
     * In-memory {@link TenantDataSourceResolver} — replaces the production file-based resolver.
     * Each tenant UUID maps to a distinct {@code jdbc:h2:mem:web-it-{uuid}} database.
     *
     * @param dataSourceProperties the Spring Boot datasource properties for username/password
     * @return an in-memory resolver
     */
    @Bean
    public TenantDataSourceResolver inMemoryTenantDataSourceResolver(
            DataSourceProperties dataSourceProperties) {
        ConcurrentHashMap<UUID, DataSource> cache = new ConcurrentHashMap<>();
        return tenantId ->
                cache.computeIfAbsent(
                        tenantId,
                        id -> {
                            String url =
                                    "jdbc:h2:mem:web-it-"
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
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Placeholder {@link AdminCredentialsProvider} — returns an empty hash string. Each controller
     * IT overrides this with a {@code @Primary} test-specific provider via its inner {@code
     * TestAdminCredentials} class.
     */
    @Bean
    public AdminCredentialsProvider adminCredentialsProvider() {
        return () -> "";
    }

    /**
     * {@link UserDetailsService} backed by the (overridable) {@link AdminCredentialsProvider}.
     *
     * @param credentialsProvider the admin credentials provider (placeholder or @Primary override)
     * @param passwordEncoder the BCrypt encoder
     */
    @Bean
    public UserDetailsService userDetailsService(
            AdminCredentialsProvider credentialsProvider, PasswordEncoder passwordEncoder) {
        return username -> {
            if (!"admin".equals(username)) {
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
                        "Unknown user: " + username);
            }
            return User.builder()
                    .username("admin")
                    .password(credentialsProvider.getPasswordHash())
                    .roles("ADMIN")
                    .build();
        };
    }

    /**
     * Production-equivalent {@link SecurityFilterChain}. Mirrors the authorization rules from
     * {@code SecurityConfig#buildSecurityFilterChain}.
     *
     * @param http the {@link HttpSecurity} builder
     * @param userDetailsService the admin user-details service
     * @throws Exception if Spring Security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, UserDetailsService userDetailsService) throws Exception {
        http.userDetailsService(userDetailsService);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/actuator/health")
                                        .permitAll()
                                        .requestMatchers("/error")
                                        .permitAll()
                                        .requestMatchers("/ws/**")
                                        .permitAll()
                                        .requestMatchers("/score/**")
                                        .permitAll()
                                        .requestMatchers("/api/devices/register")
                                        .permitAll()
                                        .requestMatchers("/api/devices/status")
                                        .permitAll()
                                        .requestMatchers("/api/score/**")
                                        .permitAll()
                                        .requestMatchers("/api/display/**")
                                        .permitAll()
                                        .requestMatchers("/display/**")
                                        .permitAll()
                                        .requestMatchers("/timer/**")
                                        .permitAll()
                                        .requestMatchers("/api/tournaments/*/audio/*/stream")
                                        .permitAll()
                                        .requestMatchers("/print/assets/**")
                                        .permitAll()
                                        .requestMatchers("/api/timer/**")
                                        .permitAll()
                                        .requestMatchers("/print/**")
                                        .authenticated()
                                        .requestMatchers("/admin/**")
                                        .authenticated()
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        .anyRequest()
                                        .authenticated())
                .httpBasic(basic -> basic.realmName("Tournament Manager"));
        return http.build();
    }

    // =========================================================================
    // Domain-layer mocks (domain.* is not a Modulith module; not loaded by
    // DIRECT_DEPENDENCIES)
    // =========================================================================

    /**
     * Mockito mock for {@link PhotoStorageService} — satisfies {@code TeamController}'s constructor
     * injection.
     */
    @Bean
    public PhotoStorageService photoStorageService() {
        return Mockito.mock(PhotoStorageService.class);
    }

    /**
     * Mockito mock for {@link ScoringRuleRegistry} — satisfies {@code TournamentService} and {@code
     * TournamentRulesController} constructor injection.
     */
    @Bean
    public ScoringRuleRegistry scoringRuleRegistry() {
        return Mockito.mock(ScoringRuleRegistry.class);
    }

    /**
     * Mockito mock for {@link SetValidationRuleRegistry} — satisfies {@code TournamentService} and
     * {@code TournamentRulesController} constructor injection.
     */
    @Bean
    public SetValidationRuleRegistry setValidationRuleRegistry() {
        return Mockito.mock(SetValidationRuleRegistry.class);
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
