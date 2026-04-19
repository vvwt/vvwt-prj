package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.tenant.LocationContext;
import de.vvwt.tm.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * STOMP {@link ChannelInterceptor} that authenticates WebSocket connections via a device token.
 *
 * <h2>Purpose (DEC-24 D2, E14S09)</h2>
 *
 * <p>At STOMP CONNECT time, extracts the {@code X-Device-Token} STOMP header, looks up the device
 * in the repository (using the default tenant in Wave-1 single-tenant mode), and binds the
 * appropriate contexts:
 *
 * <ol>
 *   <li>Sets the legacy {@link de.vvwt.tm.domain.repo.TenantContext} for repository access
 *       (parallel-phase coexistence; removed at E14S07 cutover).
 *   <li>Binds the new {@link TenantContext} to the device's tenant UUID.
 *   <li>If SCORING_TABLET with {@code location_id = NULL}: rejects with "no assigned location"
 *       (DEC-24 D1 usage constraint, E14S09 AC6).
 *   <li>If DISPLAY with {@code location_id = NULL}: accepts, marks session attribute {@value
 *       #SESSION_ATTR_OVERVIEW_MODE} = {@code true} (overview mode, E14S09 AC7).
 *   <li>If DISPLAY with {@code location_id != NULL}: binds {@link LocationContext} (AC7b).
 *   <li>Rejects for: no token, unknown token, DISCONNECTED device (AC5).
 * </ol>
 *
 * <h2>Transport (AC4)</h2>
 *
 * <p>Token arrives via STOMP CONNECT frame header {@code X-Device-Token} (constant {@link
 * WebSocketSecurityConfig#DEVICE_TOKEN_HEADER}), matching the existing STOMP pattern from E07S06 —
 * no client-side changes needed for the display SPA.
 *
 * <h2>Wave-1 assumption (DEC-24 D2)</h2>
 *
 * <p>The device lookup runs against the default tenant's DataSource. In a multi-tenant Wave-2
 * deployment this would require a cross-tenant device registry; for Wave-1, setting the legacy
 * TenantContext to the default tenant UUID before the lookup is the correct approach. A {@code //
 * TODO(Wave-2)} comment marks this assumption inline.
 *
 * <h2>Context scope (AC8)</h2>
 *
 * <p>Legacy TenantContext is set and cleared in a try-finally block around the device lookup. The
 * new TenantContext scope is stored in session attributes for cleanup on session close. On
 * rejection, all contexts are cleaned up immediately.
 *
 * @see WebSocketSecurityConfig
 * @see LocationContext
 * @see TenantContext
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E14S09.story.md">Story
 *     E14S09</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-24.md">DEC-24</a>
 */
public class DeviceTokenHandshakeInterceptor implements ChannelInterceptor {

    private static final Logger log =
            LoggerFactory.getLogger(DeviceTokenHandshakeInterceptor.class);

    /**
     * WebSocket session attribute key for "overview mode" marker (AC7).
     *
     * <p>Set to {@code Boolean.TRUE} when a DISPLAY device connects without an assigned location.
     * Downstream handlers can read this attribute to select the overview-mode payload.
     */
    public static final String SESSION_ATTR_OVERVIEW_MODE = "LOCATION_CONTEXT_OVERVIEW";

    private final DeviceRepository deviceRepository;

    /**
     * Legacy domain-layer TenantContext — needed for DeviceRepository queries during parallel
     * phase.
     */
    private final de.vvwt.tm.domain.repo.TenantContext legacyTenantContext;

    /** New tenant-api TenantContext (de.vvwt.tm.tenant.TenantContext). */
    private final TenantContext tenantContext;

    private final LocationContext locationContext;
    private final UUID defaultTenantId;

    /**
     * Constructor used by {@link WebSocketSecurityConfig}.
     *
     * @param deviceRepository repository for device lookup (uses legacy TenantContext internally)
     * @param legacyTenantContext legacy domain-layer TenantContext for repository access
     * @param tenantContext new tenant-api TenantContext for session binding
     * @param locationContext thread-local location context
     * @param defaultTenantId the default tenant UUID for Wave-1 device lookup
     */
    public DeviceTokenHandshakeInterceptor(
            DeviceRepository deviceRepository,
            de.vvwt.tm.domain.repo.TenantContext legacyTenantContext,
            TenantContext tenantContext,
            LocationContext locationContext,
            UUID defaultTenantId) {
        this.deviceRepository = deviceRepository;
        this.legacyTenantContext = legacyTenantContext;
        this.tenantContext = tenantContext;
        this.locationContext = locationContext;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Constructor for unit testing (mocks legacyTenantContext and tenantContext separately). Used
     * when defaultTenantId is provided by the test.
     */
    DeviceTokenHandshakeInterceptor(
            DeviceRepository deviceRepository,
            TenantContext tenantContext,
            LocationContext locationContext,
            UUID defaultTenantId) {
        this(deviceRepository, null, tenantContext, locationContext, defaultTenantId);
    }

    /** Constructor for unit testing (no-token path only, no defaultTenantId needed). */
    DeviceTokenHandshakeInterceptor(
            DeviceRepository deviceRepository,
            TenantContext tenantContext,
            LocationContext locationContext) {
        this(deviceRepository, null, tenantContext, locationContext, null);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message; // Not a CONNECT frame — pass through
        }

        String deviceToken =
                accessor.getFirstNativeHeader(WebSocketSecurityConfig.DEVICE_TOKEN_HEADER);
        if (deviceToken == null || deviceToken.isBlank()) {
            // No device token — pass through to let admin-basic or timer paths handle it
            return message;
        }

        authenticateDevice(accessor, deviceToken.trim());
        return message; // accessor is mutable; headers mutated in place
    }

    private void authenticateDevice(StompHeaderAccessor accessor, String deviceToken) {
        // TODO(Wave-2): for multi-tenant deployments, the device lookup must use a cross-tenant
        // device registry or a token-prefix-based routing mechanism. For Wave-1 single-tenant
        // mode, setting the legacy TenantContext to the default tenant before lookup is correct.

        // Set legacy TenantContext for DeviceRepository access (parallel-phase coexistence)
        if (legacyTenantContext != null && defaultTenantId != null) {
            legacyTenantContext.set(defaultTenantId);
        }
        try {
            Optional<Device> deviceOpt = deviceRepository.findByDeviceToken(deviceToken);

            if (deviceOpt.isEmpty()) {
                log.warn("[ws-auth] Device token not found: token={}***", safePrefix(deviceToken));
                throw new AccessDeniedException(
                        "WebSocket CONNECT rejected: invalid device token (E14S09 AC5b)");
            }

            Device device = deviceOpt.get();

            if (Device.STATUS_DISCONNECTED.equals(device.getStatus())) {
                log.warn(
                        "[ws-auth] Device rejected: status=DISCONNECTED deviceId={}",
                        device.getId());
                throw new AccessDeniedException(
                        "WebSocket CONNECT rejected: device is DISCONNECTED (E14S09 AC5c)");
            }

            if (!Device.STATUS_REGISTERED.equals(device.getStatus())
                    && !Device.STATUS_ASSIGNED.equals(device.getStatus())) {
                log.warn(
                        "[ws-auth] Device rejected: unexpected status={} deviceId={}",
                        device.getStatus(),
                        device.getId());
                throw new AccessDeniedException(
                        "WebSocket CONNECT rejected: device has inactive status (E14S09 AC5)");
            }

            if (Device.TYPE_SCORING_TABLET.equals(device.getDeviceType())
                    && device.getLocationId() == null) {
                log.warn(
                        "[ws-auth] SCORING_TABLET rejected: no assigned location deviceId={}",
                        device.getId());
                throw new AccessDeniedException(
                        "WebSocket CONNECT rejected: SCORING_TABLET has no assigned location "
                                + "(E14S09 AC6)");
            }

            // Bind new TenantContext to device's tenant for session duration
            TenantContext.Scope tenantScope = tenantContext.bind(device.getTenantId());

            // Bind LocationContext (if location assigned) or mark overview mode
            if (device.getLocationId() != null) {
                // Bind LocationContext for location-scoped display (AC7b)
                LocationContext.Scope locationScope = locationContext.bind(device.getLocationId());
                // Store scopes in session attributes for cleanup
                if (accessor.getSessionAttributes() != null) {
                    accessor.getSessionAttributes().put("_locationContextScope", locationScope);
                    accessor.getSessionAttributes().put("_tenantContextScope", tenantScope);
                }
                log.debug(
                        "[ws-auth] Device authenticated: type={} locationId={} tenantId={}",
                        device.getDeviceType(),
                        device.getLocationId(),
                        device.getTenantId());
            } else {
                // DISPLAY with null location → overview mode (AC7a)
                if (accessor.getSessionAttributes() != null) {
                    accessor.getSessionAttributes().put(SESSION_ATTR_OVERVIEW_MODE, Boolean.TRUE);
                    accessor.getSessionAttributes().put("_tenantContextScope", tenantScope);
                }
                log.debug(
                        "[ws-auth] DISPLAY device in overview mode (no location) tenantId={}",
                        device.getTenantId());
            }

            // Build principal for the session
            String principalName = "display-device:" + device.getTenantId();
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            principalName,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_DISPLAY")));
            auth.setDetails(device.getLocationId()); // null = overview mode
            accessor.setUser(auth);

        } catch (AccessDeniedException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("[ws-auth] Unexpected error during device auth", ex);
            throw new AccessDeniedException("WebSocket CONNECT rejected: internal error");
        } finally {
            // Always clear legacy TenantContext — it was only needed for the repository lookup
            if (legacyTenantContext != null) {
                legacyTenantContext.clear();
            }
        }
    }

    /** Returns a safe prefix of the token for logging (avoids logging full tokens). */
    private static String safePrefix(String token) {
        return token.length() > 8 ? token.substring(0, 8) : "***";
    }
}
