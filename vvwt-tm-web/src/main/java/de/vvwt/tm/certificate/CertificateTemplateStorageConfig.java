package de.vvwt.tm.certificate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for certificate template file storage (E12S04).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.certificate.CertificateTemplateStorageConfig} to the
 * new {@code de.vvwt.tm.certificate} Modulith module as part of E23S06 (Q-1b whole-class relocation
 * per DEC-22 §refactor-clause).
 *
 * <p>Bound to the {@code tm.certificate-templates} property namespace in {@code application.yml}.
 *
 * <p>DEC-15: the data directory must be outside the jlink archive (read-only at runtime). Template
 * files are stored in a user-writable location on the host filesystem.
 *
 * <p>Note: The bean name is {@code certificateModuleStorageConfig} to avoid {@link
 * org.springframework.beans.factory.support.BeanDefinitionOverrideException} during the parallel
 * phase when the legacy {@code de.vvwt.tm.domain.certificate.CertificateTemplateStorageConfig}
 * (bean name {@code certificateTemplateStorageConfig}) is still on the classpath. Both beans bind
 * the same {@code tm.certificate-templates} property namespace, so both receive the same
 * configuration. The legacy bean is excluded from the component scan at E23S10 Cutover-2 when the
 * legacy class is deleted.
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
 */
@Component("certificateModuleStorageConfig")
@ConfigurationProperties(prefix = "tm.certificate-templates")
public class CertificateTemplateStorageConfig {

    /**
     * Root directory for certificate template file storage. Subdirectories are created
     * automatically per tournament: {@code {dataDir}/{tournamentId}/certificate-template.{ext}}.
     *
     * <p>Default: {@code ${user.home}/.tournament-manager/certificate-templates}. Override via
     * {@code TM_CERT_TEMPLATES_DATA_DIR} env var or {@code -Dtm.certificate-templates.data-dir}.
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
