// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Tournament Manager public host override.
 *
 * <p>Binds {@code tm.public-host} from {@code application.yml} (or environment variable {@code
 * TM_PUBLIC_HOST}). When set to a non-blank value, the configured host takes precedence over
 * auto-detection in {@link DefaultLanHostDetector}.
 *
 * <h2>Purpose (E49S04)</h2>
 *
 * <p>The default (blank) causes {@link DefaultLanHostDetector} to enumerate local network
 * interfaces and select a site-local, non-loopback IPv4 address — the zero-config path for
 * single-NIC hosts. Operators on multi-homed machines (Ethernet + Wi-Fi + Docker/VPN) must set this
 * property to pin the correct NIC address.
 *
 * <h2>Invalid-value handling (AC-ERROR-INVALID-OVERRIDE-FALLBACK)</h2>
 *
 * <p>A blank value (empty string, whitespace-only) is treated as "not set" and auto-detection is
 * used. Non-blank values are used as-is without further format validation — the operator is
 * responsible for providing a valid hostname or IP address.
 *
 * @see DefaultLanHostDetector
 * @see <a href="../../../../../../../../docs/governance/stories/E49S04.story.md">Story E49S04</a>
 */
@Component
@ConfigurationProperties(prefix = "tm")
public class TmPublicHostProperties {

    private static final Logger log = LoggerFactory.getLogger(TmPublicHostProperties.class);

    /**
     * Optional explicit public host for registration and timer QR URLs.
     *
     * <p>Default: blank (auto-detection via {@link DefaultLanHostDetector}). Override via {@code
     * TM_PUBLIC_HOST} env var or {@code tm.public-host} in {@code application.yml}.
     */
    private String publicHost = "";

    /**
     * Returns the configured public-host value.
     *
     * @return the raw configured value; may be blank (meaning: use auto-detection)
     */
    public String getPublicHost() {
        return publicHost;
    }

    /**
     * Sets the public-host value. A blank or whitespace-only value is treated as "not set".
     *
     * @param publicHost the configured value; {@code null} is normalised to blank
     */
    public void setPublicHost(String publicHost) {
        if (publicHost == null || publicHost.isBlank()) {
            if (publicHost != null && !publicHost.isEmpty()) {
                // Non-null but whitespace-only: log a warning per
                // AC-ERROR-INVALID-OVERRIDE-FALLBACK
                log.warn(
                        "[E49S04] tm.public-host is set to a blank/whitespace-only value '{}' —"
                            + " falling back to auto-detection (DEC-16 zero-internet constraint)."
                            + " Set tm.public-host to a valid non-blank host to suppress this"
                            + " warning.",
                        publicHost);
            }
            this.publicHost = "";
        } else {
            this.publicHost = publicHost;
        }
    }

    /**
     * Returns {@code true} when an explicit override host is configured (non-blank).
     *
     * @return {@code true} if auto-detection should be skipped
     */
    public boolean hasOverride() {
        return !publicHost.isBlank();
    }
}
