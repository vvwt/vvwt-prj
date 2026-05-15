package de.vvwt.tm.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TmDataDirProperties}.
 *
 * <p>AC9: {@code ${tm.data.dir}} must have a documented default (platform-appropriate app-data
 * directory) and an {@code application.yml} override key.
 *
 * <p>Story: E14S02 — DEC-10/DEC-20/DEC-22.
 *
 * <p>AC-TEST-RED-FIRST-JAVA-DEFAULTS (E55S15 / DEC-68): the Java field default must resolve to
 * {@code ~/.tournament-manager} — the DEC-68 canonical root. Test was written RED against the
 * pre-change {@code ~/.vvwt-tm} default.
 */
class TmDataDirPropertiesTest {

    // -------------------------------------------------------------------------
    // AC9 — default data dir is set and is non-null / non-empty
    // -------------------------------------------------------------------------

    @Test
    void defaultDataDirIsNonEmpty() {
        TmDataDirProperties props = new TmDataDirProperties();

        String defaultDir = props.getDir();

        assertThat(defaultDir)
                .as("Default tm.data.dir must not be null or empty (AC9)")
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    void defaultDataDirIsUnderUserHome() {
        TmDataDirProperties props = new TmDataDirProperties();
        String userHome = System.getProperty("user.home");

        String defaultDir = props.getDir();

        assertThat(Paths.get(defaultDir).startsWith(Paths.get(userHome)))
                .as("Default tm.data.dir should reside under user.home (AC9 platform-appropriate)")
                .isTrue();
    }

    @Test
    void dataDirCanBeOverridden() {
        TmDataDirProperties props = new TmDataDirProperties();
        props.setDir("/custom/data/path");

        assertThat(props.getDir())
                .as("tm.data.dir must be overridable (AC9)")
                .isEqualTo("/custom/data/path");
    }

    // -------------------------------------------------------------------------
    // AC-TEST-RED-FIRST-JAVA-DEFAULTS (E55S15) — DEC-22 RED-first
    // Written RED against the pre-change ~/.vvwt-tm default.
    // Goes GREEN after AC-IMPL-TMDATADIRPROPERTIES-DEFAULT is applied.
    // -------------------------------------------------------------------------

    @Test
    void defaultDataDirIsUnderTournamentManagerRoot() {
        TmDataDirProperties props = new TmDataDirProperties();

        String defaultDir = props.getDir();

        assertThat(defaultDir)
                .as(
                        "Default tm.data.dir must resolve to ~/.tournament-manager per DEC-68"
                                + " (AC-IMPL-TMDATADIRPROPERTIES-DEFAULT, E55S15)")
                .endsWith("/.tournament-manager");
    }
}
