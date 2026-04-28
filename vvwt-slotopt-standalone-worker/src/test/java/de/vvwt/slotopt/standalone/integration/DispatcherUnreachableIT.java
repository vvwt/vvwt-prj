package de.vvwt.slotopt.standalone.integration;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test: dispatcher unreachable path.
 *
 * <p>Verifies that when the dispatcher URL points to an unreachable port (localhost:1), the worker
 * fails fast at the algorithm-announcement query step with:
 *
 * <ul>
 *   <li>A non-zero exit code ({@link ExitCode#DISPATCHER_UNREACHABLE} = 75 = EX_TEMPFAIL)
 *   <li>An error message on stderr
 * </ul>
 *
 * <p>Story: E41S06 AC-INTEGRATION-TEST-DISPATCHER-UNREACHABLE, AC-EXIT-CODE-MATRIX.
 */
class DispatcherUnreachableIT {

    @Test
    @DisplayName(
            "AC-INTEGRATION-TEST-DISPATCHER-UNREACHABLE: worker fails fast with EX_TEMPFAIL (75)"
                    + " when dispatcher is unreachable")
    void dispatcherUnreachable_failsFastWithExitCode75() {
        // Port 1 is reserved and should be unreachable
        URI unreachableUrl = URI.create("http://localhost:1");
        WorkerLauncher launcher = new WorkerLauncher(unreachableUrl);

        WorkerRunResult result = launcher.launch();

        // Worker must exit with EX_TEMPFAIL (75) = dispatcher_unreachable
        assertThat(result.exitCode())
                .as(
                        "Worker must exit 75 (EX_TEMPFAIL) when dispatcher is unreachable;"
                                + " stderr: "
                                + result.stderr())
                .isEqualTo(ExitCode.DISPATCHER_UNREACHABLE);

        // Stderr must contain an error message
        assertThat(result.stderr())
                .as("Stderr must contain an error message about the dispatcher being unreachable")
                .isNotBlank();
    }
}
