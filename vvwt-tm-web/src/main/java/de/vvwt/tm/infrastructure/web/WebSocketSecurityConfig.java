package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.tenant.DefaultTenantProvider;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WebSocket-level authentication via STOMP ChannelInterceptor.
 *
 * <h2>E05S03 — Admin auth (HTTP Basic)</h2>
 * <p>Admin SPA clients connect with {@code Authorization: Basic …} in the STOMP CONNECT frame.
 *
 * <h2>E07S06 — Display device auth (device token, AC1, AC11, AC12)</h2>
 * <p>Display devices use a device token in the STOMP CONNECT frame header
 * {@link #DEVICE_TOKEN_HEADER} ({@code X-Device-Token}). The interceptor:
 * <ol>
 *   <li>Sets TenantContext to the default tenant and looks up the device by token.</li>
 *   <li>Validates type={@code DISPLAY} and status={@code REGISTERED|ASSIGNED}.</li>
 *   <li>Sets a synthetic Principal {@code "display-device:{tenantId}"} on the STOMP session.
 *       The frontend uses this tenantId to build the subscription topic
 *       {@code /topic/display/{tenantId}/events}.</li>
 * </ol>
 *
 * <h2>Auth priority on CONNECT</h2>
 * <ol>
 *   <li>{@code X-Device-Token} present → device-token auth</li>
 *   <li>{@code Authorization: Basic …} present → admin Basic auth</li>
 *   <li>Neither → {@link AccessDeniedException}</li>
 * </ol>
 *
 * @see WebSocketConfig
 * @see DomainEventBridge
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S06.story.md">Story E07S06</a>
 */
@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSecurityConfig.class);

    /**
     * STOMP CONNECT header name for display device token (E07S06 AC1).
     * The frontend passes the device token here instead of an admin password.
     */
    public static final String DEVICE_TOKEN_HEADER = "X-Device-Token";

    private static final String DEVICE_TYPE_DISPLAY = "DISPLAY";

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final DeviceRepository deviceRepository;
    private final TenantContext tenantContext;
    private final DefaultTenantProvider defaultTenantProvider;

    public WebSocketSecurityConfig(UserDetailsService userDetailsService,
                                   PasswordEncoder passwordEncoder,
                                   DeviceRepository deviceRepository,
                                   TenantContext tenantContext,
                                   DefaultTenantProvider defaultTenantProvider) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.deviceRepository = deviceRepository;
        this.tenantContext = tenantContext;
        this.defaultTenantProvider = defaultTenantProvider;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new CombinedAuthStompInterceptor());
    }

    /**
     * STOMP ChannelInterceptor that accepts device token OR admin Basic credentials.
     */
    private final class CombinedAuthStompInterceptor implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

            if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                return message; // Not a CONNECT frame — already authenticated, pass through
            }

            // Priority 1: display device token (E07S06 AC1)
            String deviceToken = accessor.getFirstNativeHeader(DEVICE_TOKEN_HEADER);
            if (deviceToken != null && !deviceToken.isBlank()) {
                authenticateDisplayDevice(accessor, deviceToken);
                return message;
            }

            // Priority 2: admin HTTP Basic (E05S03 AC6)
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Basic ")) {
                authenticateAdminBasic(accessor, authHeader);
                return message;
            }

            throw new AccessDeniedException(
                    "WebSocket CONNECT requires X-Device-Token (display) "
                    + "or Authorization: Basic (admin) header");
        }

        // -----------------------------------------------------------------------
        // Display device token auth (E07S06 AC1, AC11, AC12)
        // -----------------------------------------------------------------------

        private void authenticateDisplayDevice(StompHeaderAccessor accessor, String deviceToken) {
            UUID defaultTenantId = defaultTenantProvider.getDefaultTenantId();
            tenantContext.set(defaultTenantId);
            try {
                Optional<Device> deviceOpt = deviceRepository.findByDeviceToken(deviceToken);

                if (deviceOpt.isEmpty()) {
                    log.debug("[ws-auth] Display device token not found");
                    throw new AccessDeniedException(
                            "WebSocket CONNECT rejected: invalid device token (E07S06 AC12)");
                }

                Device device = deviceOpt.get();

                if (!DEVICE_TYPE_DISPLAY.equals(device.getDeviceType())) {
                    log.debug("[ws-auth] Device rejected: wrong type={}", device.getDeviceType());
                    throw new AccessDeniedException(
                            "WebSocket CONNECT rejected: token is not for a DISPLAY device (E07S06 AC12)");
                }

                if (!Device.STATUS_REGISTERED.equals(device.getStatus())
                        && !Device.STATUS_ASSIGNED.equals(device.getStatus())) {
                    log.debug("[ws-auth] Device rejected: inactive status={}", device.getStatus());
                    throw new AccessDeniedException(
                            "WebSocket CONNECT rejected: display device is not active (E07S06 AC12)");
                }

                // Synthetic principal: "display-device:{tenantId}"
                // The ROLE_DISPLAY authority marks this as a display-device session.
                String principalName = "display-device:" + device.getTenantId();
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principalName, null,
                        List.of(new SimpleGrantedAuthority("ROLE_DISPLAY")));
                auth.setDetails(device.getTenantId()); // tenantId available for topic subscription

                accessor.setUser(auth);

                log.debug("[ws-auth] Display device authenticated tenantId={}", device.getTenantId());

            } finally {
                tenantContext.clear();
            }
        }

        // -----------------------------------------------------------------------
        // Admin HTTP Basic auth (E05S03 AC6 — unchanged)
        // -----------------------------------------------------------------------

        private void authenticateAdminBasic(StompHeaderAccessor accessor, String authHeader) {
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(base64Credentials),
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
                throw new AccessDeniedException(
                        "Invalid credentials for WebSocket CONNECT (AC6)");
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
            accessor.setUser(authentication);
        }
    }
}
