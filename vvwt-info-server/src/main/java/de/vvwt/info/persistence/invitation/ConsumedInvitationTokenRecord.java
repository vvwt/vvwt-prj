// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.invitation;

import java.time.LocalDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC entity record for the {@code consumed_invitation_tokens} table.
 *
 * <p>Tracks durable consumption of invitation tokens for the primary registration profile (AC10).
 * On restart, the in-memory token pool is rebuilt as:
 *
 * <pre>config-list MINUS consumed_invitation_tokens contents</pre>
 *
 * <p>Column-length pin (AC10 cycle-2 F-R3): {@code token_value VARCHAR(64)} accommodates Base64URL
 * of 256-bit cryptographically random tokens (43 chars padded; 64-char cap provides
 * forward-headroom).
 *
 * @param tokenValue the consumed invitation token value — PK
 * @param consumedAt UTC timestamp of consumption
 * @param consumedByTenantId tenant identifier that consumed this token; nullable (orphan row on
 *     roll-back before tenant insert)
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-42.md">DEC-42 D3</a>
 */
@Table("consumed_invitation_tokens")
public record ConsumedInvitationTokenRecord(
        @Id @Column("token_value") String tokenValue,
        @Column("consumed_at") LocalDateTime consumedAt,
        @Column("consumed_by_tenant_id") String consumedByTenantId) {}
