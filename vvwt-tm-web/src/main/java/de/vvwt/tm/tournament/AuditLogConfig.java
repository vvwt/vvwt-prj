// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for audit log file storage (E55S13).
 *
 * <p>Bound to the {@code tm.audit-log} property namespace in {@code application.yml}. The data
 * directory is the root under which per-tenant, per-tournament JSONL files are created:
 *
 * <pre>
 * {@code <data-dir>/tenants/<tenant>/audit-log/<tournament-id>/audit.jsonl}
 * </pre>
 *
 * <p>DEC-15: files stored outside the jlink archive in a user-writable data directory. Default:
 * {@code ~/.tournament-manager} (DEC-68 canonical root, E55S15). Override via environment variable
 * {@code TM_AUDIT_LOG_DATA_DIR} or JVM property {@code -Dtm.audit-log.data-dir}.
 *
 * <p>This Java-level default is a belt-and-suspenders fallback for an absent {@code
 * application.yml}; in normal deployments {@code application.yml} sets {@code
 * tm.audit-log.data-dir: ${TM_AUDIT_LOG_DATA_DIR:${tm.data.dir}}} and this field is overridden by
 * Spring property binding. Without this fix the Java default {@code ~/.vvwt-tm} would silently
 * re-create the two-root split DEC-68 eliminates.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultAuditLogRepository
 * @see <a href="DEC-15">DEC-15 — jlink distribution; user-writable data directory</a>
 * @see <a href="DEC-68">DEC-68 — filesystem-path canon (E55S15)</a>
 * @see <a href="E55S13">E55S13 — AC-IMPL-FILE-LAYOUT</a>
 */
@Component("auditLogConfig")
@ConfigurationProperties(prefix = "tm.audit-log")
public class AuditLogConfig {

    /**
     * Root directory for audit log file storage.
     *
     * <p>Subdirectories are created automatically: {@code
     * {dataDir}/tenants/{tenant}/audit-log/{tournamentId}/audit.jsonl}.
     *
     * <p>Default: {@code ${user.home}/.tournament-manager} (DEC-68 canonical root, E55S15).
     * Override via {@code -Dtm.audit-log.data-dir} or environment variable {@code
     * TM_AUDIT_LOG_DATA_DIR}.
     */
    private String dataDir = System.getProperty("user.home") + "/.tournament-manager";

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
