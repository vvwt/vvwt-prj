// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.persistence.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit test for {@link TenantRecord} (DEC-22 Iron Law, AC1).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S03.story.md">E38S03 AC1</a>
 */
class TenantRecordTest {

    @Test
    void record_fields_accessible() {
        var now = LocalDateTime.now();
        var publicKey = new byte[] {0x01, 0x02};
        var record = new TenantRecord("tenant-1", publicKey, "Ed25519", now, "ACTIVE", false);

        assertThat(record.tenantId()).isEqualTo("tenant-1");
        assertThat(record.publicKey()).isEqualTo(publicKey);
        assertThat(record.algorithmId()).isEqualTo("Ed25519");
        assertThat(record.registeredAt()).isEqualTo(now);
        assertThat(record.status()).isEqualTo("ACTIVE");
        assertThat(record.isDefault()).isFalse();
    }

    @Test
    void record_default_tenant_flag() {
        var record =
                new TenantRecord(
                        "default", new byte[32], "Ed25519", LocalDateTime.now(), "ACTIVE", true);
        assertThat(record.isDefault()).isTrue();
    }
}
