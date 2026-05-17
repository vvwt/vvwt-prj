// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuditLogConfig}.
 *
 * <p>AC-TEST-RED-FIRST-JAVA-DEFAULTS (E55S15 / DEC-68 / DEC-22): the Java field default for {@code
 * AuditLogConfig.dataDir} must resolve to {@code ~/.tournament-manager} — the DEC-68 canonical
 * root. This test was written RED against the pre-change {@code ~/.vvwt-tm} default and goes GREEN
 * after AC-IMPL-AUDITLOGCONFIG-DEFAULT is applied.
 */
class AuditLogConfigTest {

    // -------------------------------------------------------------------------
    // Basic contract — default is non-null / non-empty
    // -------------------------------------------------------------------------

    @Test
    void defaultDataDirIsNonEmpty() {
        AuditLogConfig config = new AuditLogConfig();

        assertThat(config.getDataDir())
                .as("Default AuditLogConfig.dataDir must not be null or empty")
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    void dataDirCanBeOverridden() {
        AuditLogConfig config = new AuditLogConfig();
        config.setDataDir("/custom/audit-log/path");

        assertThat(config.getDataDir())
                .as("AuditLogConfig.dataDir must be overridable")
                .isEqualTo("/custom/audit-log/path");
    }

    // -------------------------------------------------------------------------
    // AC-TEST-RED-FIRST-JAVA-DEFAULTS (E55S15) — DEC-22 RED-first
    // Written RED against the pre-change ~/.vvwt-tm default.
    // Goes GREEN after AC-IMPL-AUDITLOGCONFIG-DEFAULT is applied.
    // -------------------------------------------------------------------------

    @Test
    void defaultDataDirIsUnderTournamentManagerRoot() {
        AuditLogConfig config = new AuditLogConfig();

        assertThat(config.getDataDir())
                .as(
                        "Default AuditLogConfig.dataDir must resolve to ~/.tournament-manager per"
                                + " DEC-68 (AC-IMPL-AUDITLOGCONFIG-DEFAULT, E55S15)")
                .endsWith("/.tournament-manager");
    }
}
