package de.vvwt.tm.domain;

/**
 * Result of the three-step phase preparation sequence (AC1 — E05S07).
 *
 * <p>Each step result contains a success flag and a human-readable message suitable for
 * the REST response body (AC1, AC12). The message is non-null for both success and failure:
 * on success it is a brief confirmation; on failure it is the exception message from the
 * failing service call.
 *
 * <p>{@code overallSuccess} is {@code true} if and only if all three steps succeeded.
 *
 * @see PhaseLifecycleService#prepare(java.util.UUID)
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S07.story.md">Story E05S07</a>
 */
public record PhasePreparationResult(
        boolean generateMatchesSuccess,
        String  generateMatchesMessage,
        boolean optimizeSlotsSuccess,
        String  optimizeSlotsMessage,
        boolean assignRefereesSuccess,
        String  assignRefereesMessage,
        boolean overallSuccess
) {
    /**
     * Creates a result for the case where step 1 (generateMatches) failed.
     * Steps 2 and 3 are skipped.
     *
     * @param message the failure message from step 1
     * @return a result with all three steps marked as failed at step 1
     */
    public static PhasePreparationResult generateMatchesFailed(String message) {
        return new PhasePreparationResult(
                false, message,
                false, "skipped — generateMatches failed",
                false, "skipped — generateMatches failed",
                false);
    }

    /**
     * Creates a result for the case where step 2 (optimizeSlots) failed.
     * Step 3 is skipped.
     *
     * @param message the failure message from step 2
     * @return a result with step 1 succeeded, steps 2 and 3 failed
     */
    public static PhasePreparationResult optimizeSlotsFailed(String message) {
        return new PhasePreparationResult(
                true,  "Matches generated successfully.",
                false, message,
                false, "skipped — optimizeSlots failed",
                false);
    }

    /**
     * Creates a result for the case where step 3 (assignReferees) failed.
     *
     * @param message the failure message from step 3
     * @return a result with steps 1 and 2 succeeded, step 3 failed
     */
    public static PhasePreparationResult assignRefereesFailed(String message) {
        return new PhasePreparationResult(
                true,  "Matches generated successfully.",
                true,  "Slot optimization completed successfully.",
                false, message,
                false);
    }

    /**
     * Creates a result for full success of all three steps.
     *
     * @return a result with all three steps succeeded
     */
    public static PhasePreparationResult success() {
        return new PhasePreparationResult(
                true, "Matches generated successfully.",
                true, "Slot optimization completed successfully.",
                true, "Referees assigned successfully.",
                true);
    }
}
