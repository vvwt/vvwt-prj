package de.vvwt.slotopt.standalone.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithm;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithmsResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-package tests for algorithm validation logic.
 *
 * <p>These tests are in the {@code bootstrap} package. Per DEC-36: tests in a DIFFERENT package
 * from their subject MUST reference the subject via its public interface. Here we test via {@link
 * AlgorithmValidator} which is in the {@code bootstrap.internal} package — however since we are
 * testing the public surface, we use the package-accessible validator.
 *
 * <p>Story: E41S04 AC-ALGORITHM-VALIDATION, AC-DEC43-D3-ADMIN-WARNING-SURFACE,
 * AC-RECOMMENDED-MIGRATION-DERIVATION, AC-DEC43-PAST-DEPRECATION-FAIL-FAST.
 */
class AlgorithmValidationTest {

    private static final LocalDate FUTURE_DATE = LocalDate.now().plusYears(2);
    private static final LocalDate PAST_DATE = LocalDate.now().minusDays(1);

    /** TC-13: Algorithm in announced list with null deprecation_date → SUCCESS (no exception). */
    @Test
    void validate_algorithm_present_null_deprecation_succeeds() {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));

        // Should not throw
        AlgorithmValidationResult result = AlgorithmValidator.validate("Ed25519", response);
        assertThat(result.isValid()).isTrue();
        assertThat(result.hasDeprecationWarning()).isFalse();
    }

    /** TC-14: Algorithm not in announced list → BootstrapException with ALGORITHM_NOT_ANNOUNCED. */
    @Test
    void validate_algorithm_not_in_list_throws() {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));

        assertThatThrownBy(() -> AlgorithmValidator.validate("ML-DSA-65", response))
                .isInstanceOf(BootstrapException.class)
                .satisfies(
                        ex -> {
                            BootstrapException be = (BootstrapException) ex;
                            assertThat(be.getExitCode())
                                    .isEqualTo(ExitCode.ALGORITHM_NOT_ANNOUNCED);
                            assertThat(be.getMessage()).contains("algorithm not in announced list");
                        });
    }

    /**
     * TC-15: Algorithm with past deprecation_date → BootstrapException with
     * ALGORITHM_DEPRECATED_PAST_DEADLINE.
     */
    @Test
    void validate_algorithm_past_deprecation_throws() {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", PAST_DATE, null)));

        assertThatThrownBy(() -> AlgorithmValidator.validate("Ed25519", response))
                .isInstanceOf(BootstrapException.class)
                .satisfies(
                        ex -> {
                            BootstrapException be = (BootstrapException) ex;
                            assertThat(be.getExitCode())
                                    .isEqualTo(ExitCode.ALGORITHM_DEPRECATED_PAST_DEADLINE);
                            assertThat(be.getMessage())
                                    .contains("algorithm deprecated past deadline");
                        });
    }

    /** TC-16: Algorithm with future deprecation_date → WARNING (not fail-fast). */
    @Test
    void validate_algorithm_future_deprecation_returns_warning() {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(
                                new AnnouncedAlgorithm("Ed25519", "Ed25519", FUTURE_DATE, null),
                                new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", null, null)));

        AlgorithmValidationResult result = AlgorithmValidator.validate("Ed25519", response);

        assertThat(result.isValid()).isTrue();
        assertThat(result.hasDeprecationWarning()).isTrue();
        assertThat(result.deprecationDate()).isEqualTo(FUTURE_DATE);
    }

    /**
     * TC-17: Past-deprecation boundary uses DEC-48 semantics. The entire deprecation_date day is
     * the LAST accepted day; rejection starts at first instant of day AFTER deprecation_date. If
     * today IS the deprecation_date, the algorithm is still accepted (future-side check is strict:
     * today == deprecationDate is still ACCEPTED as it is not past).
     */
    @Test
    void validate_deprecation_boundary_semantics_dec48() {
        LocalDate today = LocalDate.now();
        // today is the deprecation_date → ACCEPTED (warning, not fail-fast)
        // The DEC-48 formula: accepted if
        // Instant.now().isBefore(depDate.plusDays(1).atStartOfDay(UTC))
        // Since today == depDate, plusDays(1) is tomorrow → still future → accepted with warning
        AnnouncedAlgorithmsResponse responseToday =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", today, null)));

        AlgorithmValidationResult resultToday =
                AlgorithmValidator.validate("Ed25519", responseToday);
        // Today's deprecation_date: the entire day is still accepted
        // (unless we're exactly at midnight UTC today→tomorrow boundary, but that's within
        // tolerance)
        // The key: if today is the depDate, plusDays(1) is still in the future → accepted with
        // warning
        assertThat(resultToday.isValid()).isTrue();

        // yesterday is past → fail-fast
        LocalDate yesterday = today.minusDays(1);
        AnnouncedAlgorithmsResponse responseYesterday =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", yesterday, null)));

        assertThatThrownBy(() -> AlgorithmValidator.validate("Ed25519", responseYesterday))
                .isInstanceOf(BootstrapException.class)
                .satisfies(
                        ex ->
                                assertThat(((BootstrapException) ex).getExitCode())
                                        .isEqualTo(ExitCode.ALGORITHM_DEPRECATED_PAST_DEADLINE));
    }

    /**
     * TC-21: Recommended migration = non-deprecated announced algorithms excluding the chosen one.
     */
    @Test
    void recommended_migration_excludes_chosen_and_deprecated() {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(
                                new AnnouncedAlgorithm("Ed25519", "Ed25519", FUTURE_DATE, null),
                                new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", null, null),
                                new AnnouncedAlgorithm("ML-DSA-87", "ML-DSA-87", PAST_DATE, null)));

        AlgorithmValidationResult result = AlgorithmValidator.validate("Ed25519", response);

        assertThat(result.hasDeprecationWarning()).isTrue();
        // Recommended: non-deprecated subset excluding chosen Ed25519
        // ML-DSA-65 has null deprecation (non-deprecated)
        // ML-DSA-87 has PAST_DATE (past deadline → deprecated, excluded)
        assertThat(result.recommendedMigrationTargets()).containsExactly("ML-DSA-65");
    }

    /** TC-22: Recommended migration is comma-separated in the warning messages. */
    @Test
    void recommended_migration_is_comma_separated() {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(
                                new AnnouncedAlgorithm("Ed25519", "Ed25519", FUTURE_DATE, null),
                                new AnnouncedAlgorithm("ML-DSA-65", "ML-DSA-65", null, null),
                                new AnnouncedAlgorithm("SLH-DSA-128", "SLH-DSA-128", null, null)));

        AlgorithmValidationResult result = AlgorithmValidator.validate("Ed25519", response);

        assertThat(result.recommendedMigrationCommaSeparated()).contains("ML-DSA-65");
        assertThat(result.recommendedMigrationCommaSeparated()).contains("SLH-DSA-128");
        assertThat(result.recommendedMigrationCommaSeparated()).contains(", ");
    }

    /** TC-20: No warning channels populated when deprecation_date is null. */
    @Test
    void no_warning_when_deprecation_date_null() {
        AnnouncedAlgorithmsResponse response =
                new AnnouncedAlgorithmsResponse(
                        List.of(new AnnouncedAlgorithm("Ed25519", "Ed25519", null, null)));

        AlgorithmValidationResult result = AlgorithmValidator.validate("Ed25519", response);

        assertThat(result.hasDeprecationWarning()).isFalse();
        assertThat(result.recommendedMigrationTargets()).isEmpty();
    }
}
