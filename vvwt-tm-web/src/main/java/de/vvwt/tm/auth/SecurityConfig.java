package de.vvwt.tm.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for Tournament Manager V1 admin UI.
 *
 * <p>Story E05S02 — AC4, AC5, AC6, AC7, AC8, AC12.
 *
 * <h2>Authentication model</h2>
 * <p>HTTP Basic authentication with a single fixed username {@code admin} and a
 * cryptographically generated password managed by {@link AdminCredentialsBootstrap}.
 * The password is stored as a bcrypt hash (cost 10) in the H2 {@code admin_credentials}
 * table and loaded into memory at startup.
 *
 * <h2>Authorization rules (AC4)</h2>
 * <ul>
 *   <li>{@code /actuator/health} — permit all (no auth required — E02S05 health endpoint)</li>
 *   <li>{@code /ws/**} — permit all at the HTTP Security level (WebSocket upgrade path used
 *       by E05S03; WebSocket-level auth is E05S03's responsibility per AC8)</li>
 *   <li>{@code /api/devices/register} — permit all (device registration, E06S03)</li>
 *   <li>{@code /api/devices/status} — permit all (device status polling, E06S03)</li>
 *   <li>{@code /api/score/**} — permit all (score entry API, E06S06 AC9 — device-token auth at service layer)</li>
 *   <li>{@code /score/**} — permit all (no auth — scoring tablet routes, DEC-19, E06S02 AC9)</li>
 *   <li>{@code /admin/**} — requires authentication</li>
 *   <li>{@code /api/**} — requires authentication</li>
 *   <li>All other paths — requires authentication (default deny)</li>
 * </ul>
 *
 * <h2>CSRF decision (AC12)</h2>
 * <p>CSRF protection is disabled. HTTP Basic auth is stateless — credentials are sent on
 * every request and there are no session cookies to protect against cross-site forgery.
 * If a future story switches to session-based auth (cookies), CSRF must be re-enabled.
 * This decision is documented here per AC12.
 *
 * <h2>Session policy</h2>
 * <p>Session creation is set to {@link SessionCreationPolicy#STATELESS} to match the
 * stateless HTTP Basic authentication model. The browser natively caches the basic-auth
 * credentials for the session without Spring needing to manage a session object.
 *
 * <h2>SPA credentials (AC7)</h2>
 * <p>The Svelte SPA's fetch calls use {@code credentials: 'same-origin'} (via
 * {@code src/main/ui/src/lib/api.ts}). The browser sends the cached basic-auth
 * Authorization header automatically on all same-origin requests after the initial
 * authentication dialog.
 *
 * @see AdminCredentialsBootstrap
 * @see AdminCredentialsProvider
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S02.story.md">Story E05S02</a>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Fixed admin username (V1 — not configurable). Public for use in WebSocket auth and tests (E05S03). */
    public static final String ADMIN_USERNAME = "admin";

    /**
     * BCrypt password encoder bean.
     *
     * <p>Cost factor 10 (BCrypt default): ~100ms per hash on modern hardware. Acceptable
     * for a startup operation — happens once per application lifecycle.
     * Used by {@link AdminCredentialsBootstrap} to hash the generated password and
     * by Spring Security's {@link UserDetailsService} to verify credentials on each request.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * {@link UserDetailsService} backed by the admin credentials loaded at startup.
     *
     * <p><b>Lazy resolution:</b> The {@link AdminCredentialsProvider#getPasswordHash()} call
     * is deferred to the first authentication attempt (not bean creation time). This is
     * necessary because Spring Security's {@code WebSecurityConfiguration} instantiates the
     * {@code UserDetailsService} bean during the bean factory initialization phase, which
     * executes BEFORE {@code ApplicationRunner} beans (including
     * {@link AdminCredentialsBootstrap}) have run.
     *
     * <p>The lazy {@code UserDetailsService} implementation calls
     * {@code credentialsProvider.getPasswordHash()} only when {@code loadUserByUsername()}
     * is invoked — which happens on the first HTTP request after the server has started and
     * all {@code ApplicationRunner} instances have completed. By that point the hash is
     * guaranteed to be populated.
     *
     * @param credentialsProvider provides the bcrypt hash generated at startup
     */
    @Bean
    public UserDetailsService userDetailsService(AdminCredentialsProvider credentialsProvider) {
        // Lazy: do NOT call credentialsProvider.getPasswordHash() here — the ApplicationRunner
        // that populates the hash has not run yet at bean creation time.
        return username -> {
            // Called at authentication time (after ApplicationRunner has run)
            UserDetails admin = User.builder()
                    .username(ADMIN_USERNAME)
                    .password(credentialsProvider.getPasswordHash())
                    // No roles needed in V1 — single admin account, no RBAC
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
     * Main {@link SecurityFilterChain} bean.
     *
     * <p>Configures authorization rules, HTTP Basic auth, CSRF policy, and session policy.
     * See class-level Javadoc for the full decision rationale.
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @throws Exception if security configuration fails (propagated to context startup failure)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // AC12: CSRF disabled for stateless HTTP Basic auth.
                // RATIONALE: HTTP Basic sends credentials on every request; there are no
                // session cookies that a cross-site request could hijack. If future stories
                // introduce session-based auth (cookies), this line must be removed and CSRF
                // re-enabled. See E05S02 AC12 for the governing acceptance criterion.
                .csrf(AbstractHttpConfigurer::disable)

                // Session policy: stateless — Spring Security does not create or use HTTP sessions.
                // The browser caches basic-auth credentials natively; no Spring session needed.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Authorization rules — order matters (most specific first)
                .authorizeHttpRequests(auth -> auth
                        // AC4, AC5 (health): Actuator health endpoint is public (E02S05)
                        .requestMatchers("/actuator/health").permitAll()
                        // AC8: WebSocket upgrade path — permitted at HTTP layer.
                        // WebSocket-level authentication is E05S03's responsibility.
                        .requestMatchers("/ws/**").permitAll()
                        // E06S02 AC9: All /score/** routes are accessible without admin
                        // authentication — scorekeepers do not log in. Device authentication
                        // (via device token) is deferred to E06S04. The routes do NOT expose
                        // admin-only data (AC9). Covers: /score/test (hello-world), static
                        // assets (/score/assets/vvwt-tablet.js), and the E06S01 spike page
                        // (/score/spike/**). DEC-19: scoring tablet surface.
                        .requestMatchers("/score/**").permitAll()
                        // E06S03 AC2: Device registration — no auth required; tablets don't log in.
                        // The device token (returned by /register) is the tablet's credential for
                        // subsequent polling. Admin assign/unassign/find-by-PIN remain protected.
                        .requestMatchers("/api/devices/register").permitAll()
                        // E06S03 AC3: Device status polling — public; tablet provides device token
                        // as a query param. Token validation is done at the service layer (AC8).
                        .requestMatchers("/api/devices/status").permitAll()
                        // E06S06 AC9: Score entry API is accessible without admin authentication.
                        // Device identity is validated at the service layer via device token
                        // (UnauthorizedException → 401, ForbiddenException → 403). Admin-level
                        // auth would lock out scoring tablets that never log in (DEC-19).
                        .requestMatchers("/api/score/**").permitAll()
                        // AC4: Admin UI and REST API require authentication
                        .requestMatchers("/admin/**").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        // Default: require authentication for all other paths
                        .anyRequest().authenticated()
                )

                // AC4, AC5: HTTP Basic authentication (browser native dialog)
                .httpBasic(basic -> basic
                        .realmName("Tournament Manager")
                );

        return http.build();
    }
}
