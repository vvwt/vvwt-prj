// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.registration;

/**
 * In-memory invitation-token pool for the primary registration profile (AC10).
 *
 * @see de.vvwt.info.registration.internal.DefaultInvitationTokenPool
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42 D3</a>
 */
public interface InvitationTokenPool {

    /**
     * Initializes the pool by loading consumed tokens from the DB and computing the available set.
     *
     * <p>Must be called once after construction (typically {@code @PostConstruct}).
     */
    void initialize();

    /**
     * Returns {@code true} if the given token is currently in the available pool.
     *
     * @param token the token to check
     * @return {@code true} if available; {@code false} if already consumed or unknown
     */
    boolean isAvailable(String token);

    /**
     * Atomically consumes a token: removes from in-memory pool and persists to DB.
     *
     * @param token the token to consume
     * @param tenantId the tenant that consumed the token (for auditability)
     * @throws IllegalStateException if the token is not in the available pool
     */
    void consumeToken(String token, String tenantId);
}
