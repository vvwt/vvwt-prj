package de.vvwt.tm.auth.internal;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * New (reconstruction-in-place) Security configuration for Tournament Manager (E15S04).
 *
 * <p>This class is NOT annotated with {@code @Configuration} or {@code @EnableWebSecurity}.
 * It is a factory class that provides the {@link SecurityFilterChain} and
 * {@link UserDetailsService} creation logic. All Spring wiring is done in
 * {@link AuthConfiguration} — the {@code @Configuration} class that registers the beans.
 *
 * <h2>Parallel-phase co-existence (AC8, DEC-21)</h2>
 * <p>The legacy {@code de.vvwt.tm.auth.SecurityConfig} remains active until E15S07 (atomic
 * cutover). To avoid {@code BeanDefinitionOverrideException}, this class carries no Spring
 * annotations — {@link AuthConfiguration} registers the new {@link SecurityFilterChain}
 * bean under the name {@code newSecurityFilterChain}. At E15S07 cutover, the legacy class
 * is deleted and the new bean takes the default name.
 *
 * <p>No {@code @Profile}, {@code @ConditionalOnProperty}, {@code @ConditionalOnBean},
 * or any other {@code @Conditional*} annotation is used here or in {@link AuthConfiguration}
 * (AC8, DEC-21 line 61: "No feature flags").
 *
 * <h2>Public API</h2>
 * <p>The only type from this package exposed to other modules is
 * {@link de.vvwt.tm.auth.AdminCredentialsProvider} in the root {@code auth} package.
 * {@link SecurityConfig} itself stays in {@code auth.internal} — other modules cannot
 * import it (AC4, DEC-21).
 *
 * <h2>Authorization rules</h2>
 * <p>Identical to the legacy {@code de.vvwt.tm.auth.SecurityConfig} — preserved verbatim
 * to ensure session-behaviour (AC6) and authorization-rule compatibility:
 * <ul>
 *   <li>{@code /actuator/health} — public</li>
 *   <li>{@code /ws/**} — public (WebSocket upgrade)</li>
 *   <li>{@code /score/**} — public (DEC-19, scoring tablet)</li>
 *   <li>{@code /api/devices/register} — public</li>
 *   <li>{@code /api/devices/status} — public</li>
 *   <li>{@code /api/score/**} — public</li>
 *   <li>{@code /api/display/**} — public</li>
 *   <li>{@code /display/**} — public</li>
 *   <li>{@code /timer/**} — public</li>
 *   <li>{@code /api/tournaments/{id}/audio/{name}/stream} — public</li>
 *   <li>{@code /print/assets/**} — public</li>
 *   <li>{@code /api/timer/**} — public</li>
 *   <li>{@code /print/**} — requires authentication</li>
 *   <li>{@code /admin/**} — requires authentication</li>
 *   <li>{@code /api/**} — requires authentication</li>
 *   <li>All other paths — requires authentication (default deny)</li>
 * </ul>
 *
 * <h2>Session policy</h2>
 * <p>STATELESS — matches the legacy {@code SecurityConfig} (AC6 preservation).
 *
 * <h2>CSRF</h2>
 * <p>Disabled — HTTP Basic is stateless; no session cookies to protect. Decision from
 * legacy E05S02 AC12 is preserved.
 *
 * @see AuthConfiguration
 * @see AdminCredentialsProvider
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S04.story.md">Story E15S04</a>
 * @since E15S04
 */
public final class SecurityConfig {

    /** Fixed admin username (V1 — not configurable). Package-visible for tests. */
    static final String ADMIN_USERNAME = "admin";

    // Not instantiable — all methods are static factories used by AuthConfiguration.
    private SecurityConfig() {}

    /**
     * Creates the {@link UserDetailsService} that resolves admin credentials at
     * authentication time (lazy — not at bean creation time).
     *
     * <p>The {@link AdminCredentialsProvider#getPasswordHash()} call is deferred to the
     * first authentication attempt. This is safe because the {@code ApplicationRunner}
     * that populates the hash (the new {@link AdminCredentialsBootstrap}) completes before
     * Spring accepts any HTTP requests.
     *
     * @param credentialsProvider the provider of the bcrypt hash (injected by
     *                            {@link AuthConfiguration})
     * @param passwordEncoder     the BCrypt encoder bean (for Spring Security's
     *                            {@code UserDetailsService} to use during verification)
     * @return a {@link UserDetailsService} that returns the single admin user on demand
     */
    static UserDetailsService buildUserDetailsService(AdminCredentialsProvider credentialsProvider,
                                                       PasswordEncoder passwordEncoder) {
        return username -> {
            UserDetails admin = User.builder()
                    .username(ADMIN_USERNAME)
                    .password(credentialsProvider.getPasswordHash())
                    .roles("ADMIN")
                    .build();
            if (!ADMIN_USERNAME.equals(username)) {
                throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
                        "Unknown user: " + username);
            }
            return admin;
        };
    }

    /**
     * Configures and builds the {@link SecurityFilterChain}.
     *
     * <p>Authorization rules, CSRF policy, session policy, and HTTP Basic realm are
     * identical to the legacy {@code de.vvwt.tm.auth.SecurityConfig#securityFilterChain}
     * (AC6 — session behaviour preserved; DEC-21 — no feature flags).
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the configured filter chain
     * @throws Exception if Spring Security configuration fails
     */
    static SecurityFilterChain buildSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/score/**").permitAll()
                        .requestMatchers("/api/devices/register").permitAll()
                        .requestMatchers("/api/devices/status").permitAll()
                        .requestMatchers("/api/score/**").permitAll()
                        .requestMatchers("/api/display/**").permitAll()
                        .requestMatchers("/display/**").permitAll()
                        .requestMatchers("/timer/**").permitAll()
                        .requestMatchers("/api/tournaments/*/audio/*/stream").permitAll()
                        .requestMatchers("/print/assets/**").permitAll()
                        .requestMatchers("/api/timer/**").permitAll()
                        .requestMatchers("/print/**").authenticated()
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> basic.realmName("Tournament Manager"));

        return http.build();
    }
}
