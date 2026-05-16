// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.certificate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for certificate template file storage (E12S04).
 *
 * <p>Rebuilt under DEC-22 Iron Law Q-1a RED-first TDD discipline (E36S04). All getter/setter
 * signatures, Spring property namespace, and Spring bean name preserved verbatim per
 * AC-CONFIG-BINDING-PRESERVED and Brief C-3.
 *
 * <p>Bound to the {@code tm.certificate-templates} property namespace in {@code application.yml}.
 *
 * <p>DEC-15: the data directory must be outside the jlink archive (read-only at runtime). Template
 * files are stored in a user-writable location on the host filesystem.
 *
 * <p>Bean name {@code certificateModuleStorageConfig} is preserved verbatim — consumers use
 * {@code @Qualifier("certificateModuleStorageConfig")} for injection (AC-CONFIG-BINDING-PRESERVED,
 * Brief C-3 escalation trigger if changed).
 *
 * <p>Example override:
 *
 * <pre>
 * tm:
 *   certificate-templates:
 *     data-dir: /var/tournament-manager/certificate-templates
 * </pre>
 *
 * @see CertificateTemplateService
 * @see DEC-21
 * @see DEC-35
 * @see E36S04
 */
@Component("certificateModuleStorageConfig")
@ConfigurationProperties(prefix = "tm.certificate-templates")
public class CertificateTemplateStorageConfig {

    /**
     * Root directory for certificate template file storage. Subdirectories are created
     * automatically per tournament: {@code {dataDir}/{tournamentId}/certificate-template.{ext}}.
     */
    private String dataDir;

    /**
     * Maximum allowed upload size in bytes.
     *
     * <p>AC7: uploads exceeding this limit are rejected with HTTP 400. Default: 2 MB (2097152
     * bytes) per story AC7. Override via {@code -Dtm.certificate-templates.max-size-bytes}.
     */
    private long maxSizeBytes = 2L * 1024 * 1024;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }
}
