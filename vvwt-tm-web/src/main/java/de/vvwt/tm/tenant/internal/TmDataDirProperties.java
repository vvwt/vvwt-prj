package de.vvwt.tm.tenant.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Configuration properties for the Tournament Manager data directory.
 *
 * <h2>Purpose</h2>
 * <p>Binds {@code tm.data.dir} from {@code application.yml} (or environment override)
 * to this bean. The directory is used as the root for:
 * <ul>
 *   <li>Per-tenant H2 database files: {@code ${tm.data.dir}/tenants/{uuid}/db.mv.db}</li>
 *   <li>The tenant registry: {@code ${tm.data.dir}/tenant-registry.json}</li>
 * </ul>
 *
 * <h2>Default value (AC9)</h2>
 * <p>The default is {@code ${user.home}/.vvwt-tm} — a platform-appropriate, user-scoped
 * application data directory that does not require administrator privileges.
 *
 * <h2>Override</h2>
 * <p>Set {@code tm.data.dir=/your/custom/path} in {@code application.yml} or via
 * the environment variable {@code TM_DATA_DIR} (Spring Boot property relaxed binding).
 *
 * @see TenantFileRegistry
 * @see TenantDirectoryHelper
 * @see <a href="../../../../../../../../docs/governance/stories/E14S02.story.md">Story E14S02 AC9</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 */
@Component
@ConfigurationProperties(prefix = "tm.data")
public class TmDataDirProperties {

    /**
     * Root directory for all Tournament Manager tenant data and the registry file.
     * Defaults to {@code ${user.home}/.vvwt-tm} (platform-appropriate per AC9).
     */
    private String dir = System.getProperty("user.home") + "/.vvwt-tm";

    /**
     * Returns the configured data directory path.
     *
     * @return the data directory path string; never {@code null}
     */
    public String getDir() {
        return dir;
    }

    /**
     * Sets the data directory path.
     *
     * @param dir the new data directory path; must not be {@code null}
     */
    public void setDir(String dir) {
        this.dir = dir;
    }

    /**
     * Returns the configured data directory as a {@link Path}.
     *
     * @return the data directory path; never {@code null}
     */
    public Path asPath() {
        return Path.of(dir);
    }
}
