package de.vvwt.tm.tournament;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.domain.photo.PhotoStorageService;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
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
 * {@code @TestConfiguration} for {@code @ApplicationModuleTest} controller ITs in the {@code
 * tournament} module (E31S01, DEC-38 Clause C).
 *
 * <h2>Purpose</h2>
 *
 * <p>Provides the same test-infrastructure beans as {@link TenantContextTestSupport} and the {@code
 * auth} module's security beans, but declared in the {@code de.vvwt.tm.tournament} package so that
 * Spring Modulith 1.4.6's {@code ModuleTestExecutionBeanDefinitionSelector} can classify it
 * correctly.
 *
 * <p>The original {@link TenantContextTestSupport} lives in {@code de.vvwt.tm.tenant} (test source)
 * — Modulith 1.4.6 cannot classify cross-module {@code @TestConfiguration} classes referenced via
 * {@code @Import} in module-scope tests (its bean definition filter runs before the
 * {@code @TestConfiguration} factory bean is registered). This class avoids that limitation by
 * residing in the module under test.
 *
 * <p>The {@code auth} module is not a direct dependency of {@code tournament} (per {@code
 * allowedDependencies = {"tenant"}}), so Spring Security's {@code AuthConfiguration} is not loaded
 * by {@code DIRECT_DEPENDENCIES}. This config provides a minimal equivalent security setup for IT
 * purposes: BCrypt encoder, a placeholder {@link AdminCredentialsProvider} (overridden by each IT's
 * inner {@code @Primary TestAdminCredentials}), a matching {@link UserDetailsService}, and the
 * production-equivalent {@link SecurityFilterChain}.
 *
 * <h2>Beans provided</h2>
 *
 * <ul>
 *   <li>{@link TenantContextTestSupport.Binder} — allows ITs to bind/unbind the default-tenant
 *       {@link TenantContext} in {@code @BeforeEach} / {@code @AfterEach}.
 *   <li>{@link TenantDataSourceResolver} (primary) — replaces the production file-based resolver
 *       with an in-memory H2 resolver, avoiding cross-context H2 DB sharing and Flyway conflicts.
 *   <li>{@link PasswordEncoder} ({@code BCryptPasswordEncoder}) — required by each IT's inner
 *       {@code TestAdminCredentials} class and by the {@link UserDetailsService}.
 *   <li>{@link AdminCredentialsProvider} (placeholder) — overridden by each IT's inner
 *       {@code @Primary TestAdminCredentials} bean; placeholder prevents no-bean errors during
 *       context startup before override takes effect.
 *   <li>{@link UserDetailsService} — backed by the (overridable) {@link AdminCredentialsProvider}.
 *   <li>{@link SecurityFilterChain} — production-equivalent authorization rules (HTTP Basic,
 *       STATELESS, same permit-list as {@code AuthConfiguration}).
 *   <li>{@link PhotoStorageService} mock — satisfies {@code TeamController} constructor dependency
 *       on the domain-layer photo port (domain is not a Modulith module; not loaded by
 *       DIRECT_DEPENDENCIES).
 *   <li>Scoring registry mocks ({@code ScoringRuleRegistry}, {@code SetValidationRuleRegistry})
 *       removed in E22S08: {@code TournamentRulesController} relocated to {@code web} module;
 *       {@code TournamentService} does not inject scoring registries. No remaining
 *       tournament-module controller ITs import this config (AC-S08-REVERSE-MOCKITOBEAN-REMOVAL).
 * </ul>
 *
 * @see TenantContextTestSupport
 * @see <a href="DEC-38">DEC-38 — {@code @ApplicationModuleTest} canon for reconstructed modules</a>
 * @see <a href="E31S01">E31S01 — DEC-38 Clause C migration</a>
 */
@TestConfiguration
public class TournamentModuleTestConfig {

    // =========================================================================
    // Tenant infrastructure
    // =========================================================================

    /**
     * Exposes a {@link TenantContextTestSupport.Binder} bean within the tournament module's test
     * context. Wraps the default-tenant binding logic from the {@code tenant} module.
     *
     * @param tenantContext the {@code tenantRoutingContext} bean from the tenant module
     * @param tenantRegistryPort the tenant registry for resolving the default-tenant UUID
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
     * Each tenant UUID maps to a distinct {@code jdbc:h2:mem:tournament-it-{uuid}} database.
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
                                    "jdbc:h2:mem:tournament-it-"
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
    // DIRECT_DEPENDENCIES — tournament allowedDependencies = {"tenant"})
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
     * TestAdminCredentials} class. This placeholder prevents a no-qualifying-bean error during
     * context startup (before any {@code @Primary} override is applied).
     */
    @Bean
    public AdminCredentialsProvider adminCredentialsProvider() {
        // Placeholder; overridden @Primary by each IT's TestAdminCredentials inner class.
        return () -> "";
    }

    /**
     * {@link UserDetailsService} backed by the (overridable) {@link AdminCredentialsProvider}.
     * Mirrors the lazy resolution pattern in {@code SecurityConfig#buildUserDetailsService} —
     * credentials are resolved at authentication time, not at bean creation time.
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
     * {@code SecurityConfig#buildSecurityFilterChain}: HTTP Basic, STATELESS, same permit-list.
     * Required for controller ITs to test both authenticated (201/200) and unauthenticated (401)
     * paths correctly.
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
}
