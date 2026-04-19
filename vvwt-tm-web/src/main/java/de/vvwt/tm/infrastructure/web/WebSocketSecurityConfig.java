package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.tenant.LocationContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket-level authentication via STOMP ChannelInterceptor.
 *
 * <h2>E05S03 — Admin auth (HTTP Basic)</h2>
 *
 * <p>Admin SPA clients connect with {@code Authorization: Basic …} in the STOMP CONNECT frame. The
 * admin path binds {@link de.vvwt.tm.tenant.TenantContext} to the default tenant UUID (Wave-1:
 * resolved from {@link TenantRegistryPort#findAll()}) and does NOT bind {@link LocationContext} —
 * admin sessions are not location-scoped (DEC-24 D3).
 *
 * <h2>E14S09 — Device auth via device token (DEC-24 D2)</h2>
 *
 * <p>Device clients (DISPLAY) use a device token in the STOMP CONNECT frame header {@link
 * #DEVICE_TOKEN_HEADER} ({@code X-Device-Token}). Authentication is delegated to {@link
 * DeviceTokenHandshakeInterceptor}, which:
 *
 * <ol>
 *   <li>Looks up the device by token against the default-tenant DataSource (Wave-1 assumption).
 *   <li>Binds {@link de.vvwt.tm.tenant.TenantContext} to the device's tenant UUID.
 *   <li>Binds {@link LocationContext} to the device's location (if assigned), or marks the session
 *       as "overview mode" if the device has no assigned location (DISPLAY only).
 *   <li>Rejects SCORING_TABLET devices without an assigned location.
 *   <li>Rejects missing, invalid, or DISCONNECTED tokens.
 * </ol>
 *
 * <h2>E11S05 — Timer client (unauthenticated, D-7)</h2>
 *
 * <p>The timer SPA connects without admin credentials or a device token. It signals its identity
 * via the header {@link #TIMER_CONNECT_HEADER} ({@code X-Timer-Connect: true}) and supplies the
 * tenant UUID via {@link #TIMER_TENANT_ID_HEADER} ({@code X-Timer-Tenant-Id}). The interceptor
 * creates a synthetic Principal {@code "timer-client:{tenantId}"} and allows the connection through
 * (D-7: timer is a public venue-facing page). This path is RETAINED UNCHANGED per E14S09
 * out-of-scope statement.
 *
 * <h2>Auth priority on CONNECT</h2>
 *
 * <ol>
 *   <li>{@code X-Timer-Connect: true} present → timer client (unauthenticated, E11S05 D-7)
 *   <li>{@code X-Device-Token} present → device-token auth (E14S09, DEC-24 D2)
 *   <li>{@code Authorization: Basic …} present → admin Basic auth (E05S03, DEC-24 D3)
 *   <li>None → {@link AccessDeniedException}
 * </ol>
 *
 * <h2>Default-tenant resolution (E14S11)</h2>
 *
 * <p>After E14S11 (RoutingTenantDataSource activated as {@code @Primary}), the default tenant UUID
 * is resolved via {@link TenantRegistryPort#getDefault()} — the authoritative source in the file
 * registry, which now matches the routing DataSource. The legacy SQL-query approach ({@code SELECT
 * id FROM tenants WHERE is_default = TRUE}) was removed in E14S11 because the auto-wired {@code
 * JdbcTemplate} would now route through tenant context (requiring a bound {@link
 * de.vvwt.tm.tenant.TenantContext}), and resolving the default tenant before the context is bound
 * is a circular dependency.
 *
 * @see WebSocketConfig
 * @see DomainEventBridge
 * @see DeviceTokenHandshakeInterceptor
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story
 *     E05S03</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S05.story.md">Story
 *     E11S05</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E14S09.story.md">Story
 *     E14S09</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-24.md">DEC-24</a>
 */
@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSecurityConfig.class);

    /**
     * STOMP CONNECT header name for device token (E14S09, formerly E07S06). The device client
     * passes the device token here. Transport choice: STOMP header (AC4).
     */
    public static final String DEVICE_TOKEN_HEADER = "X-Device-Token";

    /**
     * STOMP CONNECT header name that identifies a timer client (E11S05 AC1, D-7). Value must be
     * {@code "true"}. Sent by the timer SPA instead of admin credentials.
     */
    public static final String TIMER_CONNECT_HEADER = "X-Timer-Connect";

    /**
     * STOMP CONNECT header carrying the tenant UUID for the timer client (E11S05 AC1). The timer
     * SPA reads the tenant UUID from the E11S02 API response and passes it here. Used to construct
     * the Principal name {@code "timer-client:{tenantId}"}.
     */
    public static final String TIMER_TENANT_ID_HEADER = "X-Timer-Tenant-Id";

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final DeviceRepository deviceRepository;

    /** Legacy domain-layer TenantContext (needed until E15 auth cutover). */
    private final TenantContext tenantContext;

    /** New tenant-api TenantContext (de.vvwt.tm.tenant.TenantContext). */
    private final de.vvwt.tm.tenant.TenantContext newTenantContext;

    private final LocationContext locationContext;

    /**
     * {@link TenantRegistryPort} for default-tenant UUID resolution (E14S11, DEC-24 D3).
     *
     * <p>After {@code @Primary RoutingTenantDataSource} activation in E14S11, the auto-wired {@code
     * JdbcTemplate} routes through tenant context. The previous SQL-query approach ({@code SELECT
     * id FROM tenants WHERE is_default = TRUE}) was removed because querying the routing DataSource
     * before a tenant context is bound would throw {@code IllegalStateException}. {@link
     * TenantRegistryPort#getDefault()} reads the file registry directly — no tenant context binding
     * required.
     *
     * <p>TODO(Wave-2): Multi-tenant deployments will need a cross-tenant device registry.
     */
    private final TenantRegistryPort tenantRegistryPort;

    public WebSocketSecurityConfig(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            DeviceRepository deviceRepository,
            TenantContext tenantContext,
            de.vvwt.tm.tenant.TenantContext newTenantContext,
            LocationContext locationContext,
            TenantRegistryPort tenantRegistryPort) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.deviceRepository = deviceRepository;
        this.tenantContext = tenantContext;
        this.newTenantContext = newTenantContext;
        this.locationContext = locationContext;
        this.tenantRegistryPort = tenantRegistryPort;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new CombinedAuthStompInterceptor());
    }

    /**
     * STOMP ChannelInterceptor that accepts device token, timer, or admin Basic credentials.
     *
     * <p>The {@link DeviceTokenHandshakeInterceptor} is created lazily on the first CONNECT frame
     * (not at bean instantiation time) to ensure that {@link #resolveDefaultTenantId()} is called
     * AFTER {@code DefaultTenantBootstrap} (ApplicationRunner, @Order(1)) has populated the {@code
     * tenants} table. During Spring context refresh, the table is empty; ApplicationRunners run
     * after context refresh completes.
     */
    private final class CombinedAuthStompInterceptor implements ChannelInterceptor {

        /** Lazily-initialized interceptor; resolved on first STOMP CONNECT. */
        private volatile DeviceTokenHandshakeInterceptor deviceInterceptor;

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

            if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                return message; // Not a CONNECT frame — already authenticated, pass through
            }

            // Priority 1: timer client (E11S05 AC1, D-7) — unauthenticated venue display
            String timerConnect = accessor.getFirstNativeHeader(TIMER_CONNECT_HEADER);
            if ("true".equals(timerConnect)) {
                authenticateTimerClient(accessor);
                return message;
            }

            // Priority 2: device token (E14S09, DEC-24 D2)
            String deviceToken = accessor.getFirstNativeHeader(DEVICE_TOKEN_HEADER);
            if (deviceToken != null && !deviceToken.isBlank()) {
                // Delegate to DeviceTokenHandshakeInterceptor (sets principal + context)
                return ensureDeviceInterceptor().preSend(message, channel);
            }

            // Priority 3: admin HTTP Basic (E05S03 AC6, DEC-24 D3)
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Basic ")) {
                authenticateAdminBasic(accessor, authHeader);
                return message;
            }

            throw new AccessDeniedException(
                    "WebSocket CONNECT requires X-Timer-Connect (timer), X-Device-Token (device) "
                            + "or Authorization: Basic (admin) header");
        }

        /**
         * Returns the {@link DeviceTokenHandshakeInterceptor}, creating it on first call.
         *
         * <p>Double-checked locking with {@code volatile} field — correct under Java Memory Model.
         * Safe because the interceptor is stateless after creation (immutable fields).
         */
        private DeviceTokenHandshakeInterceptor ensureDeviceInterceptor() {
            DeviceTokenHandshakeInterceptor interceptor = deviceInterceptor;
            if (interceptor == null) {
                synchronized (this) {
                    interceptor = deviceInterceptor;
                    if (interceptor == null) {
                        UUID defaultTenantId = resolveDefaultTenantId();
                        interceptor =
                                new DeviceTokenHandshakeInterceptor(
                                        deviceRepository,
                                        tenantContext,
                                        newTenantContext,
                                        locationContext,
                                        defaultTenantId);
                        deviceInterceptor = interceptor;
                    }
                }
            }
            return interceptor;
        }

        // -----------------------------------------------------------------------
        // Timer client auth (E11S05 AC1, D-7) — unauthenticated venue display
        // -----------------------------------------------------------------------

        private void authenticateTimerClient(StompHeaderAccessor accessor) {
            String tenantIdHeader = accessor.getFirstNativeHeader(TIMER_TENANT_ID_HEADER);

            // Build a synthetic principal for the timer client.
            String principalName =
                    "timer-client:" + (tenantIdHeader != null ? tenantIdHeader.trim() : "unknown");

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            principalName, null, List.of(new SimpleGrantedAuthority("ROLE_TIMER")));
            accessor.setUser(auth);

            log.debug("[ws-auth] Timer client connected principalName={}", principalName);
        }

        // -----------------------------------------------------------------------
        // Admin HTTP Basic auth (E05S03 AC6, DEC-24 D3)
        // -----------------------------------------------------------------------

        private void authenticateAdminBasic(StompHeaderAccessor accessor, String authHeader) {
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            String decoded;
            try {
                decoded =
                        new String(
                                Base64.getDecoder().decode(base64Credentials),
                                StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new AccessDeniedException("Malformed Authorization: Basic header (AC6)");
            }

            int colonIndex = decoded.indexOf(':');
            if (colonIndex < 0) {
                throw new AccessDeniedException(
                        "Invalid Authorization: Basic credentials format (AC6)");
            }

            String username = decoded.substring(0, colonIndex);
            String password = decoded.substring(colonIndex + 1);

            var userDetails = userDetailsService.loadUserByUsername(username);
            if (!passwordEncoder.matches(password, userDetails.getPassword())) {
                throw new AccessDeniedException("Invalid credentials for WebSocket CONNECT (AC6)");
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
            accessor.setUser(authentication);

            // DEC-24 D3: admin sessions bind TenantContext to default tenant in Wave-1.
            // LocationContext is NOT bound — admin sessions are not location-scoped (AC12).
            UUID defaultTenantId = resolveDefaultTenantId();
            if (defaultTenantId != null) {
                de.vvwt.tm.tenant.TenantContext.Scope scope =
                        newTenantContext.bind(defaultTenantId);
                if (accessor.getSessionAttributes() != null) {
                    accessor.getSessionAttributes().put("_adminTenantScope", scope);
                }
            }

            log.debug("[ws-auth] Admin authenticated username={}", username);
        }
    }

    /**
     * Resolves the default tenant UUID for Wave-1 single-tenant deployments (DEC-24 D3).
     *
     * <p>Uses {@link TenantRegistryPort#getDefault()} (E14S11, replacing the legacy SQL-query
     * approach removed per the E14S11 TODO). The registry is the authoritative source after
     * {@code @Primary RoutingTenantDataSource} activation — it returns the UUID registered by
     * {@code DefaultTenantBootstrapRunner}, which is the same UUID that routes per-tenant JDBC.
     *
     * <p>Returns {@code null} (with a warning) if the registry has no default tenant registered yet
     * (e.g. before bootstrap completes in edge-case integration scenarios).
     *
     * <p>TODO(Wave-2): Multi-tenant deployments will need a cross-tenant device registry.
     */
    private UUID resolveDefaultTenantId() {
        try {
            return tenantRegistryPort.getDefault();
        } catch (IllegalStateException e) {
            log.warn(
                    "[ws-auth] Default tenant not yet registered in tenant registry — "
                            + "cannot resolve default tenant UUID: {}",
                    e.getMessage());
            return null;
        }
    }
}
