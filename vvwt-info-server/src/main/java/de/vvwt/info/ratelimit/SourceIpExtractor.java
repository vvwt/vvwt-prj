// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts the effective source IP address from an HTTP request, applying the configured
 * trusted-proxy policy (E38S07 AC9).
 *
 * <p>Default-deny: when the trusted-proxies list is empty, {@code X-Forwarded-For} is ALWAYS
 * ignored and {@link HttpServletRequest#getRemoteAddr()} is used. This prevents XFF spoofing from
 * bypassing per-IP rate limits.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC9</a>
 */
public interface SourceIpExtractor {

    /**
     * Returns the effective client IP address for rate-limiting purposes.
     *
     * @param request the incoming HTTP request
     * @return the resolved source IP; never null
     */
    String extract(HttpServletRequest request);
}
