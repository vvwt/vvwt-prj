// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.ratelimit;

import de.vvwt.info.ratelimit.internal.RateLimitType;

/**
 * Per-IP token-bucket rate limiter (E38S07 AC2, AC8).
 *
 * <p>Attempts to consume one token for the given source IP and request type. Returns {@code true}
 * if the request is within the limit; {@code false} if rate-limited.
 *
 * @see de.vvwt.info.ratelimit.internal.DefaultIpRateLimiter
 * @see <a href="../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC2, AC8</a>
 */
public interface IpRateLimiter {

    /**
     * Attempts to consume one token for the given source IP and request type.
     *
     * @param sourceIp the resolved source IP address
     * @param type the rate-limit category
     * @return {@code true} if the request is within the limit; {@code false} if rate-limited
     */
    boolean tryConsume(String sourceIp, RateLimitType type);
}
