package de.vvwt.tm.infoportal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring {@code @ConfigurationProperties} for the public-info-portal publisher feature (AC6).
 *
 * <p>Binding prefix: {@code info-portal}.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code info-portal.url} — required string; no default. If absent, publisher feature is
 *       disabled (INFO log at startup; admin UI shows feature-disabled state).
 *   <li>{@code info-portal.tenant-id} — required string when {@code info-portal.url} is set; the
 *       tenant identifier this TM claims on the info-server.
 *   <li>{@code info-portal.location-id} — required string per Brief D-2 (location-id always
 *       non-null in the registration tuple).
 *   <li>{@code info-portal.deprecation-warning-threshold-days} — int, default 30; when an active
 *       algorithm's {@code deprecation_date} is within this threshold, admin UI banner becomes
 *       HIGH-severity (DEC-43 D3).
 * </ul>
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09 AC6</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D3 — deprecation threshold</a>
 */
@ConfigurationProperties(prefix = "info-portal")
public class InfoPortalProperties {

    /** Info-portal server URL (e.g., {@code https://info.example.com}). Null = feature disabled. */
    private String url;

    /** Tenant identifier this TM instance claims. Required when {@code url} is set. */
    private String tenantId;

    /** Location identifier. Required per Brief D-2. */
    private String locationId;

    /**
     * Days-until-deprecation threshold for HIGH-severity admin banner. Default 30 per AC6.
     * When the active algorithm's {@code deprecation_date} is within this many days, the admin UI
     * banner is elevated to HIGH severity.
     */
    private int deprecationWarningThresholdDays = 30;

    /**
     * Directory for storing the Ed25519 keypair files (AES-GCM encrypted at rest, AC8).
     * Default: {@code ${user.home}/.tournament-manager/info-portal-keys}.
     * Override via {@code INFO_PORTAL_KEYPAIR_DIR} env var or
     * {@code -Dinfo-portal.keypair-dir=/your/path}.
     */
    private String keypairDir = System.getProperty("user.home")
            + "/.tournament-manager/info-portal-keys";

    /** Returns {@code true} when the info-portal URL is configured (feature is enabled). */
    public boolean isEnabled() {
        return url != null && !url.isBlank();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public int getDeprecationWarningThresholdDays() {
        return deprecationWarningThresholdDays;
    }

    public void setDeprecationWarningThresholdDays(int deprecationWarningThresholdDays) {
        this.deprecationWarningThresholdDays = deprecationWarningThresholdDays;
    }

    public String getKeypairDir() {
        return keypairDir;
    }

    public void setKeypairDir(String keypairDir) {
        this.keypairDir = keypairDir;
    }
}
