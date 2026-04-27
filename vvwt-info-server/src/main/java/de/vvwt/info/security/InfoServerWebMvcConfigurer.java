package de.vvwt.info.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configurer that adds security headers to SPA static-resource responses (E38S08 AC11,
 * AC12).
 *
 * <p>AC11: Adds {@code Content-Security-Policy} header to all responses under the {@code /info/}
 * path. The CSP is strict: default-src 'self'; no inline scripts/styles (Svelte 5 build output has
 * no inline content by default); {@code connect-src} allows same-origin WebSocket via {@code wss:};
 * {@code frame-ancestors 'none'} defends clickjacking.
 *
 * <p>AC12: Adds {@code Referrer-Policy: no-referrer} header to the same path, preventing
 * tournament_token + team_token leakage via Referer header on outbound link clicks.
 *
 * <p>The interceptor is path-scoped to {@code /info/**} — it does not apply to API endpoints or
 * publisher/registration routes, keeping the blast radius minimal.
 *
 * <p>Story: E38S08. DEC-42 D5: internet-facing posture requires these headers.
 */
@Component
public class InfoServerWebMvcConfigurer implements WebMvcConfigurer {

    /**
     * CSP for the SPA static-resource path (AC11).
     *
     * <p>{@code connect-src 'self' wss:} allows WebSocket to same-origin host on both ws:// and
     * wss:// (self-host may use plain ws; primary MUST use wss). Using {@code wss:} rather than a
     * dynamic hostname avoids an @Value injection but is still restrictive (no third-party ws).
     */
    static final String CSP_VALUE =
            "default-src 'self'; "
                    + "img-src 'self' data:; "
                    + "style-src 'self'; "
                    + "script-src 'self'; "
                    + "connect-src 'self' wss:; "
                    + "frame-ancestors 'none'; "
                    + "base-uri 'self'; "
                    + "form-action 'self'";

    static final String REFERRER_POLICY_VALUE = "no-referrer";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SpaHeaderInterceptor()).addPathPatterns("/info/**", "/info");
    }

    /** Interceptor that writes CSP + Referrer-Policy on every SPA path response. */
    private static class SpaHeaderInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(
                HttpServletRequest request, HttpServletResponse response, Object handler) {
            response.setHeader("Content-Security-Policy", CSP_VALUE);
            response.setHeader("Referrer-Policy", REFERRER_POLICY_VALUE);
            return true;
        }
    }
}
