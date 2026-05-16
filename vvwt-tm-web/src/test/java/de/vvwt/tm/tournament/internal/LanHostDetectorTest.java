// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.LocalAddressSupplier;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultLanHostDetector} — Stories E49S04 and E49S05.
 *
 * <p>RED-first per DEC-22 Iron Law. These tests exercise new behaviour (host detection and config
 * override). They were RED before {@link DefaultLanHostDetector} was authored (E49S04).
 *
 * <p>E49S05 restructuring (AC-TEST-EXERCISES-PRODUCTION-CODE): deterministic address control is now
 * achieved by mocking {@link LocalAddressSupplier} — the genuine production collaborator — rather
 * than through the removed test-only 2-arg constructor seam. The test logic and branch coverage are
 * preserved in full (AC-TEST-DETERMINISTIC-COVERAGE-RETAINED).
 *
 * <h2>Tests (AC-TEST-LAN-HOST-DETECTED-RED, AC-TEST-CONFIG-OVERRIDE-WINS-RED)</h2>
 *
 * @see DefaultLanHostDetector
 * @see LocalAddressSupplier
 * @see <a href="../../../../../../../../docs/governance/stories/E49S04.story.md">Story E49S04</a>
 * @see <a href="../../../../../../../../docs/governance/stories/E49S05.story.md">Story E49S05</a>
 */
@ExtendWith(MockitoExtension.class)
class LanHostDetectorTest {

    @Mock private TmPublicHostProperties properties;

    /**
     * Mock of the production {@link LocalAddressSupplier} collaborator. Injected via {@link
     * DefaultLanHostDetector#DefaultLanHostDetector(TmPublicHostProperties, LocalAddressSupplier)}.
     * Tests that need deterministic address lists stub this mock's {@code getLocalAddresses()}.
     */
    @Mock private LocalAddressSupplier localAddressSupplier;

    /** System under test — single production constructor wired by Mockito {@link InjectMocks}. */
    @InjectMocks private DefaultLanHostDetector detector;

    // ─────────────────────────────────────────────────────────────────────────
    // AC-TEST-CONFIG-OVERRIDE-WINS-RED
    // When an explicit override is configured, it must be returned and auto-detection
    // must NOT be consulted.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void detectHost_returnsOverride_whenOverrideIsConfigured() {
        // given
        when(properties.hasOverride()).thenReturn(true);
        when(properties.getPublicHost()).thenReturn("192.168.100.42");

        // when
        String host = detector.detectHost();

        // then
        assertThat(host).isEqualTo("192.168.100.42");
    }

    @Test
    void detectHost_returnsOverride_notAutoDetected_whenOverrideSet() {
        // given — the override is "10.0.1.55" (a private-range address distinct from any
        // auto-detected value the real NetworkInterface enumeration might produce)
        when(properties.hasOverride()).thenReturn(true);
        when(properties.getPublicHost()).thenReturn("10.0.1.55");

        // when
        String host = detector.detectHost();

        // then — must be the configured value, not whatever auto-detection would yield
        assertThat(host).isEqualTo("10.0.1.55");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-TEST-LAN-HOST-DETECTED-RED / AC-SECURITY-LOCAL-ONLY-PRESERVED
    // When no override is configured and the address supplier returns at least one
    // site-local non-loopback address, detectHost() returns it.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void detectHost_returnsSiteLocalAddress_whenSupplierProvidesSiteLocalAddress()
            throws UnknownHostException {
        // given — no override; supplier returns one site-local address
        when(properties.hasOverride()).thenReturn(false);
        InetAddress siteLocal = InetAddress.getByName("192.168.1.99");
        when(localAddressSupplier.getLocalAddresses()).thenReturn(List.of(siteLocal));

        // when
        String host = detector.detectHost();

        // then — must return the site-local address (AC-TEST-LAN-HOST-DETECTED-RED)
        assertThat(host).isEqualTo("192.168.1.99");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-ERROR-NO-LAN-INTERFACE-FALLBACK
    // When the supplier returns only loopback addresses, the fallback must be
    // returned gracefully without throwing.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void detectHost_returnsLoopbackFallback_whenNoSiteLocalInterfaceFound() {
        // given — no override; supplier returns only a loopback address
        when(properties.hasOverride()).thenReturn(false);
        when(localAddressSupplier.getLocalAddresses())
                .thenReturn(List.of(InetAddress.getLoopbackAddress()));

        // when
        String host = detector.detectHost();

        // then — must not throw; must return the documented fallback
        assertThat(host).isEqualTo(DefaultLanHostDetector.LOOPBACK_FALLBACK);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-ERROR-MULTI-INTERFACE-DETERMINISM
    // When multiple site-local addresses exist, detectHost() must return exactly ONE
    // (the first one in list order) without failing or returning empty.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void detectHost_returnsSingleAddress_whenMultipleSiteLocalInterfacesExist()
            throws UnknownHostException {
        // given — no override; two site-local addresses available
        when(properties.hasOverride()).thenReturn(false);
        InetAddress addr1 = InetAddress.getByName("192.168.1.10");
        InetAddress addr2 = InetAddress.getByName("10.0.0.5");
        when(localAddressSupplier.getLocalAddresses()).thenReturn(List.of(addr1, addr2));

        // when
        String host = detector.detectHost();

        // then — first site-local address is returned
        assertThat(host).isEqualTo("192.168.1.10");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-SECURITY-SITE-LOCAL-PREFERENCE
    // Auto-detection must prefer site-local over non-site-local when both are available.
    // (Public addresses must not be silently selected.)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void detectHost_prefersSiteLocal_overPublicAddress() throws UnknownHostException {
        // given — no override; one public + one site-local address
        when(properties.hasOverride()).thenReturn(false);
        InetAddress publicAddr = InetAddress.getByName("8.8.8.8");
        InetAddress siteLocalAddr = InetAddress.getByName("192.168.50.100");
        when(localAddressSupplier.getLocalAddresses())
                .thenReturn(List.of(publicAddr, siteLocalAddr));

        // when
        String host = detector.detectHost();

        // then — must return the site-local address, NOT the public one
        assertThat(host).isEqualTo("192.168.50.100");
    }
}
