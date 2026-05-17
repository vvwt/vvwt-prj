// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.vvwt.tm.tenant.LocationContext;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link DeviceTokenHandshakeInterceptor} (E14S09, AC1).
 *
 * <p>Verifies token extraction, context binding, and rejection semantics using Mockito doubles for
 * {@link DeviceRepository}, {@link TenantContext}, and {@link LocationContext}. Characterization
 * tests against the legacy {@code authenticateDisplayDevice} path in {@link
 * WebSocketSecurityConfig} are forbidden per DEC-22.
 *
 * @see DeviceTokenHandshakeInterceptor
 */
@ExtendWith(MockitoExtension.class)
class DeviceTokenHandshakeInterceptorTest {

    private static final UUID DEFAULT_TENANT_ID = UUID.randomUUID();
    private static final UUID LOCATION_ID = UUID.randomUUID();

    @Mock private DeviceRepository deviceRepository;
    @Mock private TenantContext tenantContext;
    @Mock private LocationContext locationContext;
    @Mock private MessageChannel channel;

    private DeviceTokenHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor =
                new DeviceTokenHandshakeInterceptor(
                        deviceRepository, tenantContext, locationContext);
    }

    // -------------------------------------------------------------------------
    // AC5a — no token → rejected
    // -------------------------------------------------------------------------

    @Test
    void noDeviceToken_preSend_isNotHandledByInterceptor() {
        // When no device token header is present, the interceptor returns the message unchanged
        // (the surrounding WebSocketSecurityConfig will handle admin-basic or reject)
        Message<?> msg = buildConnectMessage(null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).as("AC5a: no-token message passed through unchanged").isSameAs(msg);
        verifyNoInteractions(deviceRepository);
    }

    // -------------------------------------------------------------------------
    // AC5b — unknown token → rejected
    // -------------------------------------------------------------------------

    @Test
    void unknownDeviceToken_preSend_throwsAccessDenied() {
        String token = UUID.randomUUID().toString();
        // Device not found
        when(deviceRepository.findByDeviceToken(token)).thenReturn(Optional.empty());
        // E14S11: lookup scope is opened and closed even when device is not found
        TenantContext.Scope lookupScope = mock(TenantContext.Scope.class);
        when(tenantContext.bind(DEFAULT_TENANT_ID)).thenReturn(lookupScope);
        interceptor =
                new DeviceTokenHandshakeInterceptor(
                        deviceRepository, tenantContext, locationContext, DEFAULT_TENANT_ID);

        Message<?> msg = buildConnectMessage(token);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .as("AC5b: unknown token must throw AccessDeniedException")
                .isInstanceOf(AccessDeniedException.class);

        // E14S11: TenantContext.bind() is called for the lookup scope, then closed
        verify(tenantContext).bind(DEFAULT_TENANT_ID);
        verify(lookupScope).close();
        // No session-duration scope is opened — only the lookup scope
        verifyNoMoreInteractions(tenantContext);
    }

    // -------------------------------------------------------------------------
    // AC5c — DISCONNECTED device → rejected
    // -------------------------------------------------------------------------

    @Test
    void disconnectedDevice_preSend_throwsAccessDenied() {
        String token = UUID.randomUUID().toString();
        when(deviceRepository.findByDeviceToken(token))
                .thenReturn(
                        Optional.of(
                                makeDevice(
                                        Device.TYPE_DISPLAY,
                                        Device.STATUS_DISCONNECTED,
                                        LOCATION_ID)));
        // E14S11: lookup scope is opened and closed; device validation rejects in Phase 2
        TenantContext.Scope lookupScope = mock(TenantContext.Scope.class);
        when(tenantContext.bind(DEFAULT_TENANT_ID)).thenReturn(lookupScope);
        interceptor =
                new DeviceTokenHandshakeInterceptor(
                        deviceRepository, tenantContext, locationContext, DEFAULT_TENANT_ID);

        Message<?> msg = buildConnectMessage(token);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .as("AC5c: DISCONNECTED device must throw AccessDeniedException")
                .isInstanceOf(AccessDeniedException.class);

        // E14S11: lookup scope bind+close is called; no session-duration bind after rejection
        verify(tenantContext).bind(DEFAULT_TENANT_ID);
        verify(lookupScope).close();
        verifyNoMoreInteractions(tenantContext);
    }

    // -------------------------------------------------------------------------
    // AC6 — SCORING_TABLET with null locationId → rejected
    // -------------------------------------------------------------------------

    @Test
    void scoringTablet_nullLocation_preSend_throwsAccessDeniedWithLocationMessage() {
        String token = UUID.randomUUID().toString();
        when(deviceRepository.findByDeviceToken(token))
                .thenReturn(
                        Optional.of(
                                makeDevice(
                                        Device.TYPE_SCORING_TABLET,
                                        Device.STATUS_REGISTERED,
                                        null)));
        // E14S11: lookup scope is opened and closed; type+location check rejects in Phase 2
        TenantContext.Scope lookupScope = mock(TenantContext.Scope.class);
        when(tenantContext.bind(DEFAULT_TENANT_ID)).thenReturn(lookupScope);
        interceptor =
                new DeviceTokenHandshakeInterceptor(
                        deviceRepository, tenantContext, locationContext, DEFAULT_TENANT_ID);

        Message<?> msg = buildConnectMessage(token);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
                .as("AC6: SCORING_TABLET with null locationId must be rejected")
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("no assigned location");

        // E14S11: lookup scope bind+close is called; no session-duration bind after rejection
        verify(tenantContext).bind(DEFAULT_TENANT_ID);
        verify(lookupScope).close();
        verifyNoMoreInteractions(tenantContext);
    }

    // -------------------------------------------------------------------------
    // AC7a — DISPLAY with null locationId → accepted, OVERVIEW marker set
    // -------------------------------------------------------------------------

    @Test
    void displayDevice_nullLocation_preSend_succeedsWithOverviewMarker() {
        String token = UUID.randomUUID().toString();
        TenantContext.Scope mockScope = mock(TenantContext.Scope.class);
        when(tenantContext.bind(any())).thenReturn(mockScope);
        when(deviceRepository.findByDeviceToken(token))
                .thenReturn(
                        Optional.of(
                                makeDevice(Device.TYPE_DISPLAY, Device.STATUS_REGISTERED, null)));
        interceptor =
                new DeviceTokenHandshakeInterceptor(
                        deviceRepository, tenantContext, locationContext, DEFAULT_TENANT_ID);

        Message<?> msg = buildConnectMessageWithAttributes(token);
        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNotNull();
        // Principal should be set to indicate display-device connection
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).as("AC7a: principal must be set").isNotNull();
        assertThat(accessor.getUser().getName())
                .as("AC7a: principal name must indicate display-device")
                .startsWith("display-device:");

        // LocationContext must NOT be bound (null location)
        verifyNoInteractions(locationContext);

        // Scope must be kept open (context remains for session duration)
        // Note: in unit test, scope is kept open; session lifecycle closes it
    }

    // -------------------------------------------------------------------------
    // AC7b — DISPLAY with non-null locationId → accepted, LocationContext bound
    // -------------------------------------------------------------------------

    @Test
    void displayDevice_withLocation_preSend_bindsLocationContext() {
        String token = UUID.randomUUID().toString();
        TenantContext.Scope tenantScope = mock(TenantContext.Scope.class);
        LocationContext.Scope locationScope = mock(LocationContext.Scope.class);
        when(tenantContext.bind(any())).thenReturn(tenantScope);
        when(locationContext.bind(LOCATION_ID)).thenReturn(locationScope);
        when(deviceRepository.findByDeviceToken(token))
                .thenReturn(
                        Optional.of(
                                makeDevice(
                                        Device.TYPE_DISPLAY,
                                        Device.STATUS_REGISTERED,
                                        LOCATION_ID)));
        interceptor =
                new DeviceTokenHandshakeInterceptor(
                        deviceRepository, tenantContext, locationContext, DEFAULT_TENANT_ID);

        Message<?> msg = buildConnectMessageWithAttributes(token);
        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNotNull();
        verify(locationContext).bind(LOCATION_ID);
    }

    // -------------------------------------------------------------------------
    // Non-CONNECT frame: pass through unchanged
    // -------------------------------------------------------------------------

    @Test
    void nonConnectFrame_preSend_passesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/test");
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        verifyNoInteractions(deviceRepository, tenantContext, locationContext);
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private Message<?> buildConnectMessage(String deviceToken) {
        return buildConnectMessageWithAttributes(deviceToken);
    }

    private Message<?> buildConnectMessageWithAttributes(String deviceToken) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("test-session-" + UUID.randomUUID());
        accessor.setSessionAttributes(new HashMap<>());
        if (deviceToken != null) {
            accessor.setNativeHeader(WebSocketSecurityConfig.DEVICE_TOKEN_HEADER, deviceToken);
        }
        // setLeaveMutable(true) ensures the accessor remains mutable after MessageBuilder wraps it.
        // This is the same pattern used by Spring's WebSocket test support.
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Device makeDevice(String type, String status, UUID locationId) {
        return new Device(
                UUID.randomUUID(),
                locationId,
                "token-" + UUID.randomUUID(),
                null,
                type,
                null,
                status,
                LocalDateTime.now(),
                null,
                type.equals(Device.TYPE_DISPLAY) ? "Test Display" : null,
                null);
    }
}
