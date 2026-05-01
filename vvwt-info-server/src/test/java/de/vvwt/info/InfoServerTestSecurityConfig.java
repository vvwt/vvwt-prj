package de.vvwt.info;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-scoped security configuration for vvwt-info-server integration tests (E42S01).
 *
 * <p>vvwt-info-server has no authentication requirements — all endpoints are public. However,
 * Spring Boot 4.x requires {@code spring-boot-security} on the test classpath for
 * {@code @AutoConfigureMockMvc} to function ({@code SecurityMockMvcAutoConfiguration}, pulled
 * transitively via {@code spring-boot-test-classic-modules → spring-boot-security-test}). Without
 * an explicit {@link SecurityFilterChain}, Spring Security auto-configuration applies HTTP Basic
 * auth and CSRF protection to all endpoints, causing ITs to receive 401/403 instead of the expected
 * responses.
 *
 * <p>This {@code @Configuration} registers a permit-all {@link SecurityFilterChain} with CSRF
 * disabled. Being a plain {@code @Configuration} (not {@code @TestConfiguration}), it is picked up
 * automatically by {@code @SpringBootTest}'s component scan of the {@code de.vvwt.info} package. It
 * lives only in {@code src/test/java} and is never included in production builds.
 *
 * <p>{@code @ConditionalOnClass(HttpSecurity.class)} ensures this configuration is only applied
 * when Spring Security is active (i.e., when security auto-configuration is imported by
 * {@code @AutoConfigureMockMvc} or {@code @AutoConfigureTestRestTemplate}). In {@code
 * webEnvironment = NONE} test contexts (e.g. {@link InfoServerBootsTest}), Spring Security is not
 * activated and {@link HttpSecurity} is not a bean — without this condition the context would fail
 * with {@code NoSuchBeanDefinitionException}.
 *
 * <p>Story: E42S01 (Spring Boot 4.x migration).
 */
@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class InfoServerTestSecurityConfig {

    /**
     * Permit-all {@link SecurityFilterChain} for test contexts.
     *
     * <p>Disables CSRF (not needed for REST/WebSocket ITs) and permits all HTTP requests so that
     * production endpoint assertions are not blocked by the security layer.
     */
    @Bean
    public SecurityFilterChain testPermitAllSecurityFilterChain(HttpSecurity http)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
