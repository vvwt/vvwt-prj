package de.vvwt.tm.tenant.internal;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Tournament Manager data directory.
 *
 * <h2>Purpose</h2>
 *
 * <p>Binds {@code tm.data.dir} from {@code application.yml} (or environment override) to this bean.
 * The directory is used as the root for:
 *
 * <ul>
 *   <li>Per-tenant H2 database files: {@code ${tm.data.dir}/tenants/{uuid}/db.mv.db}
 *   <li>The tenant registry: {@code ${tm.data.dir}/tenant-registry.json}
 * </ul>
 *
 * <h2>Default value (AC9 / DEC-68)</h2>
 *
 * <p>The default is {@code ${user.home}/.tournament-manager} — the canonical Tournament Manager
 * data root per DEC-68 (E55S15). This Java-level default is a belt-and-suspenders fallback for an
 * absent {@code application.yml}; in normal deployments {@code application.yml} sets {@code
 * tm.data.dir: ${TM_DATA_DIR:${user.home}/.tournament-manager}} and this field is overridden by
 * Spring property binding before any consumer reads it.
 *
 * <h2>Override</h2>
 *
 * <p>Set {@code tm.data.dir=/your/custom/path} in {@code application.yml} or via the environment
 * variable {@code TM_DATA_DIR} (Spring Boot property relaxed binding).
 *
 * @see TenantFileRegistry
 * @see TenantDirectoryHelper
 * @see <a href="../../../../../../../../docs/governance/stories/E14S02.story.md">Story E14S02
 *     AC9</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-20.md">DEC-20</a>
 * @see <a href="../../../../../../../../docs/governance/decisions/DEC-68.md">DEC-68 —
 *     filesystem-path canon</a>
 */
@Component
@ConfigurationProperties(prefix = "tm.data")
public class TmDataDirProperties {

    /**
     * Root directory for all Tournament Manager tenant data and the registry file. Defaults to
     * {@code ${user.home}/.tournament-manager} (DEC-68 canonical root; platform-appropriate per
     * AC9).
     */
    private String dir = System.getProperty("user.home") + "/.tournament-manager";

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
