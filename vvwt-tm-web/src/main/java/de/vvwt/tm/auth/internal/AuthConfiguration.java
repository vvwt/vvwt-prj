package de.vvwt.tm.auth.internal;

import de.vvwt.tm.auth.AdminCredentialsBootstrap;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring {@code @Configuration} that wires the {@code auth} module beans after the E15S07 atomic
 * cutover.
 *
 * <p>This class is the single {@code @Configuration} + {@code @EnableWebSecurity} entry point for
 * the {@code auth} bounded context. It replaces the deleted legacy the deleted legacy root-package
 * SecurityConfig and activates all new beans as the sole implementations.
 *
 * <h2>Post-cutover bean registry (E15S07)</h2>
 *
 * <ul>
 *   <li>{@code passwordEncoder} — BCrypt(10) encoder.
 *   <li>{@code adminCredentialsBootstrap} — {@link AdminCredentialsBootstrap} with per-tenant
 *       DataSource. Bean name has no suffix — the legacy {@code @Component} bean is gone.
 *   <li>{@code adminCredentialsProvider} — {@link AdminCredentialsProvider} lambda delegating to
 *       {@link AdminCredentialsBootstrap#getPasswordHash()}.
 *   <li>{@code userDetailsService} — lazy {@link UserDetailsService} backed by {@link
 *       AdminCredentialsProvider}; consumed by {@code WebSocketSecurityConfig}.
 *   <li>{@code securityFilterChain} — the active {@link SecurityFilterChain}.
 * </ul>
 *
 * <h2>DEC-21 compliance</h2>
 *
 * <p>No {@code @Profile}, {@code @ConditionalOnProperty}, {@code @ConditionalOnBean},
 * {@code @ConditionalOnMissingBean}, {@code @ConditionalOnClass}, {@code @ConditionalOnExpression},
 * {@code @ConditionalOnJava}, {@code @ConditionalOnResource}, {@code @ConditionalOnWebApplication}
 * is used. The collision sources (legacy beans) are gone.
 *
 * @see AdminCredentialsBootstrap
 * @see SecurityConfig
 * @see AdminCredentialsProvider
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S07.story.md">Story
 *     E15S07</a>
 * @since E15S04 (created); E15S07 (promoted to full @EnableWebSecurity configuration)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AuthConfiguration {

    // -------------------------------------------------------------------------
    // Password encoder
    // -------------------------------------------------------------------------

    /**
     * BCrypt password encoder (cost 10).
     *
     * <p>Moved here from the deleted legacy root-package SecurityConfig at E15S07.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // -------------------------------------------------------------------------
    // Bootstrap + credentials provider
    // -------------------------------------------------------------------------

    /**
     * Admin-credentials bootstrap ({@link AdminCredentialsBootstrap}).
     *
     * <p>Receives the default tenant's per-tenant DataSource at {@code run()} time via {@link
     * DefaultTenantDataSourceAdapter} — a lazy {@link javax.sql.DataSource} wrapper that resolves
     * {@link TenantRegistryPort#getDefault()} + {@link
     * TenantDataSourceResolver#resolve(java.util.UUID)} on the first {@code getConnection()} call.
     * Resolution is deferred to {@code run()} time because the default tenant is registered by
     * {@code DefaultTenantBootstrapRunner} ({@code @Order(1)}) before this bean's {@code run()}
     * method fires ({@code @Order(2)}).
     *
     * <p>Bean name is {@code adminCredentialsBootstrap} — the legacy {@code @Component} bean is
     * gone, so no name suffix is needed.
     *
     * @param tenantRegistryPort provides {@link TenantRegistryPort#getDefault()} for default-tenant
     *     lookup
     * @param tenantDataSourceResolver resolves the per-tenant DataSource from a tenant UUID
     * @param passwordEncoder BCrypt encoder (defined above)
     */
    @Bean
    public AdminCredentialsBootstrap adminCredentialsBootstrap(
            TenantRegistryPort tenantRegistryPort,
            TenantDataSourceResolver tenantDataSourceResolver,
            PasswordEncoder passwordEncoder) {
        DefaultTenantDataSourceAdapter perTenantDataSource =
                new DefaultTenantDataSourceAdapter(tenantRegistryPort, tenantDataSourceResolver);
        AdminCredentialsDao dao = new AdminCredentialsDao(perTenantDataSource);
        PasswordGenerator generator = new PasswordGenerator(new SecureRandom());
        return new DefaultAdminCredentialsBootstrap(generator, dao, passwordEncoder);
    }

    /**
     * {@link AdminCredentialsProvider} bean — delegates to {@link
     * AdminCredentialsBootstrap#getPasswordHash()} at authentication time.
     *
     * <p>Previously absent during the parallel phase to avoid {@code
     * NoUniqueBeanDefinitionException}. Post-cutover, the legacy bean is gone and this is the sole
     * {@code AdminCredentialsProvider}.
     *
     * @param bootstrap the credentials bootstrap bean (wired above)
     */
    @Bean
    public AdminCredentialsProvider adminCredentialsProvider(AdminCredentialsBootstrap bootstrap) {
        return bootstrap::getPasswordHash;
    }

    // -------------------------------------------------------------------------
    // UserDetailsService + Spring Security filter chain
    // -------------------------------------------------------------------------

    /**
     * Lazy {@link UserDetailsService} bean backed by the admin credentials loaded at startup.
     *
     * <p>The {@link AdminCredentialsProvider#getPasswordHash()} call is deferred to the first
     * {@code loadUserByUsername()} invocation — which is guaranteed to occur after all {@code
     * ApplicationRunner} instances (including {@code AdminCredentialsBootstrap}) have finished.
     * This prevents a circular initialization failure at bean-creation time.
     *
     * <p>Exposed as a named bean so that {@code WebSocketSecurityConfig} can autowire it directly
     * (preserved behaviour from the deleted legacy root-package {@code SecurityConfig}).
     *
     * @param credentialsProvider the admin credentials provider (wired above)
     * @param passwordEncoder the BCrypt encoder (wired above)
     * @return a lazy {@link UserDetailsService} that resolves the admin user on demand
     */
    @Bean
    public UserDetailsService userDetailsService(
            AdminCredentialsProvider credentialsProvider, PasswordEncoder passwordEncoder) {
        return SecurityConfig.buildUserDetailsService(credentialsProvider, passwordEncoder);
    }

    /**
     * The active {@link SecurityFilterChain}.
     *
     * <p>Registers the {@code UserDetailsService} and builds the chain via {@link SecurityConfig}'s
     * static factory methods. Authorization rules, CSRF policy, session policy, and HTTP Basic
     * realm are identical to the deleted legacy chain (AC2).
     *
     * @param http the {@link HttpSecurity} builder
     * @param userDetailsService the admin user-details service (wired above)
     * @throws Exception if Spring Security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, UserDetailsService userDetailsService) throws Exception {
        http.userDetailsService(userDetailsService);
        return SecurityConfig.buildSecurityFilterChain(http);
    }
}
