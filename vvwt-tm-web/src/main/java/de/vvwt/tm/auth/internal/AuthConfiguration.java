package de.vvwt.tm.auth.internal;

import java.security.SecureRandom;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Spring {@code @Configuration} that wires the new {@code auth} module beans (E15S04).
 *
 * <p>This class is the single {@code @Configuration} entry point for the new
 * reconstruction-in-place {@code auth} bounded context. It registers all new beans in {@code
 * de.vvwt.tm.auth.internal} as Spring beans.
 *
 * <h2>Parallel-phase co-existence (AC8, DEC-21)</h2>
 *
 * <p>During the parallel phase, the legacy {@code de.vvwt.tm.auth.SecurityConfig} is also active.
 * To avoid bean collisions:
 *
 * <ul>
 *   <li>This class is {@code @Configuration} only — NOT {@code @EnableWebSecurity}. The legacy
 *       {@code SecurityConfig} owns {@code @EnableWebSecurity}.
 *   <li>No {@code SecurityFilterChain} bean is registered here during the parallel phase — Spring
 *       Security 6 forbids two "any request" filter chains in the same context. The new {@link
 *       SecurityConfig} factory class is validated via unit/IT tests that exercise its static
 *       factory methods; the live chain is activated at E15S07 cutover.
 *   <li>No {@code @Profile}, {@code @ConditionalOnProperty}, {@code @ConditionalOnBean},
 *       {@code @ConditionalOnMissingBean}, or any other {@code @Conditional*} annotation is used
 *       anywhere in this class or in {@link SecurityConfig} (AC8, DEC-21).
 * </ul>
 *
 * <h2>Bean registry (E15S04)</h2>
 *
 * <ul>
 *   <li>{@code adminCredentialsBootstrapNew} — {@link AdminCredentialsBootstrap} wired with the new
 *       internal DAO and generator; reuses the existing {@code passwordEncoder} bean from the
 *       legacy {@code SecurityConfig} (no duplicate encoder in parallel phase).
 * </ul>
 *
 * <p>Note: {@code adminCredentialsProvider} ({@code de.vvwt.tm.auth.AdminCredentialsProvider}
 * lambda) is NOT registered during the parallel phase. The legacy {@code adminCredentialsBootstrap}
 * component is the sole {@code AdminCredentialsProvider} in the running context until E15S07 atomic
 * cutover. Registering a second {@code AdminCredentialsProvider} bean without {@code @Primary}
 * causes {@code NoUniqueBeanDefinitionException} at the legacy {@code
 * SecurityConfig.userDetailsService()} injection point.
 *
 * <h2>E15S07 cutover</h2>
 *
 * <p>At cutover: this class gains {@code @EnableWebSecurity}, an {@code adminCredentialsProvider}
 * bean method, and a {@code securityFilterChain} bean method (replacing the legacy chain); legacy
 * {@code de.vvwt.tm.auth.SecurityConfig} is deleted. No {@code @Order} disambiguation is needed
 * after cutover.
 *
 * @see SecurityConfig
 * @see AdminCredentialsBootstrap
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E15S04.story.md">Story
 *     E15S04</a>
 * @since E15S04
 */
@Configuration
public class AuthConfiguration {

    // -------------------------------------------------------------------------
    // Bootstrap + credentials provider
    // -------------------------------------------------------------------------

    /**
     * New admin-credentials bootstrap ({@link AdminCredentialsBootstrap} from {@code
     * auth.internal}).
     *
     * <p>Named {@code adminCredentialsBootstrapNew} to avoid collision with the legacy {@code
     * adminCredentialsBootstrap} bean (registered by the legacy {@code
     * de.vvwt.tm.auth.AdminCredentialsBootstrap @Component}).
     *
     * <p>Receives a per-tenant DataSource from Spring (DEC-20: {@code RoutingTenantDataSource} is
     * {@code @Primary} since E14S11). The DAO wraps this DataSource for per-tenant credential
     * isolation.
     *
     * <p>Reuses the existing {@code passwordEncoder} bean from the legacy {@code
     * de.vvwt.tm.auth.SecurityConfig} (same BCrypt(10) encoder). During the parallel phase,
     * registering a duplicate encoder would cause autowire ambiguity. At E15S07 cutover, the legacy
     * encoder is deleted and this bean becomes the sole encoder.
     *
     * @param dataSource the per-tenant DataSource (resolved by {@code RoutingTenantDataSource})
     * @param passwordEncoder the existing BCrypt encoder bean (from legacy {@code SecurityConfig})
     */
    @Bean
    public AdminCredentialsBootstrap adminCredentialsBootstrapNew(
            DataSource dataSource, PasswordEncoder passwordEncoder) {
        AdminCredentialsDao dao = new AdminCredentialsDao(dataSource);
        PasswordGenerator generator = new PasswordGenerator(new SecureRandom());
        return new AdminCredentialsBootstrap(generator, dao, passwordEncoder);
    }
}
