package de.vvwt.tm.phaselifecycle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.phaselifecycle.internal.DefaultCancelFlagRegistry;
import de.vvwt.tm.phaselifecycle.internal.DefaultJobDrainService;
import de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleJobRepository;
import de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleOrchestrator;
import de.vvwt.tm.phaselifecycle.internal.DefaultWorkerRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Placeholder-implementation fail-fast unit tests — AC-ERROR-HANDLING-PLACEHOLDER-IMPL-FAIL-FAST.
 *
 * <p>RED-first per DEC-22 Iron Law Pattern B. The RED commit is authored before the {@code
 * Default*} classes exist; this test class fails to compile. GREEN state: all 5 classes exist with
 * {@code UnsupportedOperationException} methods whose message contains the expected implementing
 * Story number.
 *
 * <p>Each test verifies that a placeholder method on the corresponding {@code Default*} class
 * throws {@code UnsupportedOperationException} with a message that includes the implementing Story
 * reference (e.g., "E55S04"). This ensures that any accidental production-time invocation fails
 * fast with an operator-actionable error message.
 *
 * <p>Authorizing decisions: DEC-58 (universal interface mandate — placeholder impls in .internal),
 * DEC-64 D-14 (five beans enumerated), Story AC-ERROR-HANDLING-PLACEHOLDER-IMPL-FAIL-FAST.
 *
 * @since E55S01
 */
class PlaceholderImplFailFastTest {

  private static final UUID ANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void defaultPhaseLifecycleOrchestratorThrowsUnsupported() {
    var impl = new DefaultPhaseLifecycleOrchestrator();
    assertThatThrownBy(() -> impl.tick(ANY_ID))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("E55S04");
  }

  @Test
  void defaultPhaseLifecycleJobRepositoryThrowsUnsupported() {
    var impl = new DefaultPhaseLifecycleJobRepository();
    assertThatThrownBy(() -> impl.findNextPendingJobIdForTournament(ANY_ID))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("E55S02");
  }

  @Test
  void defaultWorkerRegistryThrowsUnsupported() {
    var impl = new DefaultWorkerRegistry();
    assertThatThrownBy(() -> impl.getOrCreate(ANY_ID))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("E55S03");
  }

  @Test
  void defaultJobDrainServiceThrowsUnsupported() {
    var impl = new DefaultJobDrainService();
    assertThatThrownBy(() -> impl.drainNext(ANY_ID))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("E55S04");
  }

  @Test
  void defaultCancelFlagRegistryThrowsUnsupported() {
    var impl = new DefaultCancelFlagRegistry();
    assertThatThrownBy(() -> impl.requestCancel(ANY_ID))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("E55S05");
  }
}
