package de.vvwt.tm.domain;

/**
 * Thrown by {@link CascadeRecomputeService} when step 1 (set validation) rejects the input (E03S11, AC3).
 *
 * <p>This is an unchecked exception so that Spring's {@code @Transactional} handling
 * automatically triggers rollback — no {@code rollbackFor} attribute needed.
 *
 * <p>Callers that need to distinguish a validation failure from other runtime exceptions
 * can catch this type specifically.
 *
 * @see de.vvwt.tm.domain.rules.SetValidationRule
 * @see de.vvwt.tm.domain.rules.ValidationResult
 */
public class ValidationException extends RuntimeException {

    /** Human-readable reason returned by the {@link de.vvwt.tm.domain.rules.ValidationResult}. */
    private final String validationReason;

    /**
     * Constructs a {@code ValidationException} with the given validation rejection reason.
     *
     * @param validationReason the reason from {@link de.vvwt.tm.domain.rules.ValidationResult#getReason()}
     *                         (must not be {@code null} or blank)
     */
    public ValidationException(String validationReason) {
        super("Set validation failed: " + validationReason);
        this.validationReason = validationReason;
    }

    /**
     * Returns the validation rejection reason from the {@link de.vvwt.tm.domain.rules.SetValidationRule}.
     *
     * @return the reason string (never {@code null})
     */
    public String getValidationReason() {
        return validationReason;
    }
}
