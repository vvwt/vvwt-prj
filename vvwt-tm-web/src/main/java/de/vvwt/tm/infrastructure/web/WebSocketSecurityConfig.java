package de.vvwt.tm.infrastructure.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * WebSocket-level authentication via STOMP ChannelInterceptor (AC6, E05S03).
 *
 * <h2>Why a ChannelInterceptor and not Spring Security WebSocket support?</h2>
 * <p>Spring Security 6.x deprecated {@code AbstractSecurityWebSocketMessageBrokerConfigurer}
 * in favour of a {@code ChannelInterceptor} approach. The interceptor validates credentials
 * on the STOMP {@code CONNECT} frame — the right level for WebSocket auth.
 *
 * <h2>Auth flow (AC6)</h2>
 * <ol>
 *   <li>HTTP layer ({@link de.vvwt.tm.auth.SecurityConfig}): {@code /ws/**} is {@code permitAll}
 *       so the SockJS handshake and STOMP upgrade can proceed to Spring WebSocket.</li>
 *   <li>STOMP layer (this interceptor): the {@code CONNECT} frame must carry an
 *       {@code Authorization: Basic …} header. If missing or invalid, the interceptor
 *       throws {@link org.springframework.messaging.simp.stomp.StompHeaderAccessor} with
 *       a rejection response.</li>
 *   <li>Subsequent frames ({@code SUBSCRIBE}, {@code SEND}): already authenticated — the
 *       Principal set during CONNECT is carried on the WebSocket session.</li>
 * </ol>
 *
 * <h2>Stateless per HTTP session (E05S02 AC12)</h2>
 * <p>HTTP Basic auth is stateless. WebSocket sessions are separate — they maintain their
 * own session lifecycle independent of HTTP sessions. The Principal is stored on the
 * WebSocket session, not on an HTTP session.
 *
 * @see WebSocketConfig
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 */
@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    public WebSocketSecurityConfig(UserDetailsService userDetailsService,
                                   PasswordEncoder passwordEncoder) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers the authentication interceptor on the inbound channel.
     *
     * <p>The interceptor fires on every inbound STOMP frame. For {@code CONNECT} frames,
     * it extracts and validates the {@code Authorization: Basic} header. For all other
     * frames, it is a no-op (the Principal is already set from the CONNECT exchange).
     *
     * @param registration the inbound channel registration
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new BasicAuthStompInterceptor());
    }

    /**
     * STOMP ChannelInterceptor that validates HTTP Basic credentials on CONNECT frames.
     *
     * <p>On success: sets the authenticated {@link java.security.Principal} on the
     * STOMP session (via {@link StompHeaderAccessor#setUser}).
     * On failure: throws {@link org.springframework.security.access.AccessDeniedException}
     * which Spring WebSocket translates to an error response with HTTP 401.
     */
    private final class BasicAuthStompInterceptor implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

            if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                // Not a CONNECT frame — pass through (already authenticated)
                return message;
            }

            // Extract Authorization header from STOMP CONNECT frame
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Basic ")) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "WebSocket CONNECT requires Authorization: Basic header (AC6)");
            }

            // Decode and validate Basic credentials
            String base64Credentials = authHeader.substring("Basic ".length()).trim();
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(base64Credentials),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Malformed Authorization: Basic header (AC6)");
            }

            int colonIndex = decoded.indexOf(':');
            if (colonIndex < 0) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Invalid Authorization: Basic credentials format (AC6)");
            }

            String username = decoded.substring(0, colonIndex);
            String password = decoded.substring(colonIndex + 1);

            // Load user and verify password
            var userDetails = userDetailsService.loadUserByUsername(username);
            if (!passwordEncoder.matches(password, userDetails.getPassword())) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Invalid credentials for WebSocket CONNECT (AC6)");
            }

            // Set authenticated principal on the STOMP session
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
            accessor.setUser(authentication);

            return message;
        }
    }
}
