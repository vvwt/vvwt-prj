// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

/**
 * Service that resolves the LAN-reachable host for use in registration and timer QR URLs.
 *
 * <p>Implementations enumerate the server's own local network interfaces and select a site-local,
 * non-loopback address (RFC 1918 / private range). A configuration override takes precedence over
 * auto-detection when set to a non-blank value.
 *
 * <p>Constraint: MUST NOT make any outbound network call. Detection works with zero internet
 * connectivity (DEC-15 / DEC-16).
 *
 * @see de.vvwt.tm.tournament.internal.DefaultLanHostDetector
 * @see <a href="../../../../../../../../docs/governance/stories/E49S04.story.md">Story E49S04</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-15.md">DEC-15</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-16.md">DEC-16</a>
 */
public interface LanHostDetector {

    /**
     * Returns the effective public host for use in registration and timer URLs.
     *
     * <p>When {@code tm.public-host} is set to a non-blank value the configured host is returned
     * directly. Otherwise auto-detection is performed: the machine's local network interfaces are
     * enumerated and the first site-local, non-loopback IPv4 address is selected.
     *
     * <p>If no site-local, non-loopback address can be detected the implementation falls back to a
     * single documented choice (e.g. {@code localhost}) so the admin UI never crashes.
     *
     * @return the effective host string, never {@code null} or blank
     */
    String detectHost();
}
