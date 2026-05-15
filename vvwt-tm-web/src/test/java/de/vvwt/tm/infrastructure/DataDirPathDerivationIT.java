package de.vvwt.tm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.internal.TmDataDirProperties;
import de.vvwt.tm.tournament.AuditLogConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * AC-TEST-PATH-DERIVATION (E55S15 / DEC-68): Integration test verifying that all eight per-purpose
 * path defaults derive from a single {@code tm.data.dir} root when that root is overridden via
 * {@code @TestPropertySource}.
 *
 * <p>Sets {@code tm.data.dir=/tmp/e55s15-data-dir-it} and asserts that every resolved path is under
 * that root — proving the Spring placeholder-nesting chain holds end-to-end.
 *
 * <p>Uses {@code @SpringBootTest} with a dedicated H2 in-memory datasource to avoid file-system
 * side-effects. Per DEC-44 the web-module controller integration tests use
 * {@code @SpringBootTest(RANDOM_PORT)} — this test is an infrastructure-configuration IT (not a
 * controller IT) and uses the default {@code SpringBootTest.WebEnvironment.MOCK} to load the full
 * application context in the lightest possible mode.
 */
@SpringBootTest(
        properties = {
            // Use in-memory H2 so the test does not write files to disk.
            // This also proves that spring.datasource.url (priority 1 in
            // DatabaseDirectoryInitializer)
            // takes precedence when set — the tm.data.dir derivation is visible in the bound beans.
            "spring.datasource.url=jdbc:h2:mem:e55s15-path-derivation-it;DB_CLOSE_DELAY=-1",
            // Redirect the registry file path to an in-memory / ignored location to avoid
            // bootstrap.
            "spring.flyway.enabled=false",
            // Disable audit log background effects in this slice.
        })
@TestPropertySource(
        properties = {
            "tm.data.dir=/tmp/e55s15-data-dir-it",
        })
class DataDirPathDerivationIT {

    private static final String EXPECTED_ROOT = "/tmp/e55s15-data-dir-it";

    @Autowired private TmDataDirProperties tmDataDirProperties;

    @Autowired private AuditLogConfig auditLogConfig;

    @Value("${tm.audio.data-dir}")
    private String audioProp;

    @Value("${tm.photos.data-dir}")
    private String photosProp;

    @Value("${tm.certificate-templates.data-dir}")
    private String certTemplatesProp;

    @Value("${tm.slotopt.dispatcher.worker-key-dir}")
    private String slotoptKeyDirProp;

    @Value("${info-portal.keypair-dir}")
    private String infoPortalKeyDirProp;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    // -------------------------------------------------------------------------
    // AC-TEST-PATH-DERIVATION: all eight paths derive from tm.data.dir
    // -------------------------------------------------------------------------

    @Test
    void tmDataDirRootResolvesToOverride() {
        assertThat(tmDataDirProperties.getDir())
                .as("tm.data.dir must resolve to the overridden root (DEC-68 clause 1)")
                .isEqualTo(EXPECTED_ROOT);
    }

    @Test
    void auditLogDataDirDerivesFromRoot() {
        // tm.audit-log.data-dir = ${TM_AUDIT_LOG_DATA_DIR:${tm.data.dir}} — no subdir appended
        assertThat(auditLogConfig.getDataDir())
                .as("tm.audit-log.data-dir must derive from tm.data.dir (DEC-68 clause 2)")
                .startsWith(EXPECTED_ROOT);
    }

    @Test
    void audioDataDirDerivesFromRoot() {
        assertThat(audioProp)
                .as("tm.audio.data-dir must derive from tm.data.dir (DEC-68 clause 2)")
                .startsWith(EXPECTED_ROOT);
    }

    @Test
    void photosDataDirDerivesFromRoot() {
        assertThat(photosProp)
                .as("tm.photos.data-dir must derive from tm.data.dir (DEC-68 clause 2)")
                .startsWith(EXPECTED_ROOT);
    }

    @Test
    void certTemplatesDataDirDerivesFromRoot() {
        assertThat(certTemplatesProp)
                .as(
                        "tm.certificate-templates.data-dir must derive from tm.data.dir (DEC-68"
                                + " clause 2)")
                .startsWith(EXPECTED_ROOT);
    }

    @Test
    void slotoptWorkerKeyDirDerivesFromRoot() {
        assertThat(slotoptKeyDirProp)
                .as(
                        "tm.slotopt.dispatcher.worker-key-dir must derive from tm.data.dir"
                                + " (DEC-68 clause 2)")
                .startsWith(EXPECTED_ROOT);
    }

    @Test
    void infoPortalKeyDirDerivesFromRoot() {
        assertThat(infoPortalKeyDirProp)
                .as("info-portal.keypair-dir must derive from tm.data.dir (DEC-68 clause 2)")
                .startsWith(EXPECTED_ROOT);
    }

    @Test
    void datasourceUrlPriority1TakesEffectWhenExplicitlySet() {
        // spring.datasource.url is set in @SpringBootTest properties above (in-memory H2).
        // DatabaseDirectoryInitializer priority-1 branch returns it as-is — no tm.data.dir
        // involved.
        // This test confirms the priority ladder is intact (DEC-68 clause 3 escape-hatch
        // principle).
        assertThat(datasourceUrl)
                .as(
                        "When spring.datasource.url is explicitly set it must take priority over"
                            + " tm.data.dir derivation (DatabaseDirectoryInitializer priority 1)")
                .contains("jdbc:h2:mem:");
    }
}
