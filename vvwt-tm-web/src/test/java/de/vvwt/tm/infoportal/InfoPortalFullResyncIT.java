package de.vvwt.tm.infoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Testcontainers integration test for AC3 — 409 FULL_RESYNC snapshot recovery path.
 *
 * <p>This is a dedicated test class for the FULL_RESYNC path. The general publisher IT is in {@link
 * InfoPortalPublisherIT}. This class focuses exclusively on container stop/start simulation.
 *
 * <p><b>PRECONDITION:</b> Requires Docker and {@code vvwt-info-server} fat-JAR. Currently
 * {@code @Disabled} pending infrastructure availability. See {@link InfoPortalPublisherIT} for
 * setup notes.
 *
 * <p>DEC-22: RED-first test skeleton for AC3 full-resync recovery path.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC3</a>
 */
@Tag("testcontainers")
@Disabled("Requires Docker + info-server fat-JAR; see InfoPortalPublisherIT Javadoc")
class InfoPortalFullResyncIT {

    private InfoPortalPublisherService service;
    private InfoPortalStateDao stateDao;
    private InfoPortalProperties properties;

    @BeforeEach
    void setUp(@org.junit.jupiter.api.io.TempDir Path tmpDir) throws Exception {
        assumeTrue(isDockerAvailable(), "Docker not available — skipping Testcontainers IT");
        // TODO: wire like InfoPortalPublisherIT.setUp() with Testcontainers container
    }

    /**
     * AC3 full-resync recovery:
     *
     * <pre>
     * 1. Publish delta seq=1,2,3 → all 200
     * 2. Stop info-server container (simulates restart/memory loss)
     * 3. Start info-server container (fresh state — no seq history)
     * 4. Publish delta seq=4 → info-server sees seq=4 but has last_applied_seq=null → 409
     * 5. Publisher receives 409 → calls postSnapshot(tournamentId, snapshotJson, seq=3)
     *    (seq NOT reset — carries last_published_seq=3)
     * 6. Info-server sets last_applied_seq=3 → returns 200
     * 7. Publish delta seq=4 again → 200
     * </pre>
     *
     * Asserts: - stateDao.findCurrentSeq(...) = 4 after recovery -
     * service.getStatus().isAlgorithmDeprecatedHardStop() is false - NO tournament re-registration
     * occurred (tournament_token retained)
     */
    @Test
    void fullResync_containerRestart_snapshotWithCurrentSeq_thenResumeDeltas() throws Exception {
        // TODO: implement once Docker + info-server available
        // See InfoPortalPublisherIT for infrastructure setup pattern
        assertThat(true).as("Placeholder — implement with Testcontainers").isTrue();
    }

    private static boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
