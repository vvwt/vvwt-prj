package de.vvwt.tm.domain.certificate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for certificate template file storage (E12S04).
 *
 * <p>Bound to the {@code tm.certificate-templates} property namespace in {@code application.yml}.
 *
 * <p>DEC-15: the data directory must be outside the jlink archive (read-only at runtime).
 * Template files are stored in a user-writable location on the host filesystem.
 *
 * <p>Example override:
 * <pre>
 * tm:
 *   certificate-templates:
 *     data-dir: /var/tournament-manager/certificate-templates
 * </pre>
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story E12S04</a>
 */
@Component
@ConfigurationProperties(prefix = "tm.certificate-templates")
public class CertificateTemplateStorageConfig {

    /**
     * Root directory for certificate template file storage.
     * Subdirectories are created automatically per tournament:
     * {@code {dataDir}/{tournamentId}/certificate-template.{ext}}.
     *
     * <p>Default: {@code ${user.home}/.tournament-manager/certificate-templates}.
     * Override via {@code TM_CERT_TEMPLATES_DATA_DIR} env var or
     * {@code -Dtm.certificate-templates.data-dir}.
     */
    private String dataDir;

    /**
     * Maximum allowed upload size in bytes.
     *
     * <p>AC7: uploads exceeding this limit are rejected with HTTP 400.
     * Default: 2 MB (2097152 bytes) per story AC7.
     * Override via {@code -Dtm.certificate-templates.max-size-bytes}.
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
