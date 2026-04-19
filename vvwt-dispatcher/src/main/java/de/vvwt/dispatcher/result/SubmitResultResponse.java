package de.vvwt.dispatcher.result;

/**
 * Response body for {@code POST /submit-result} (E01S08 AC3, AC4, AC11).
 *
 * <p>All fields use {@code Boolean} (boxed) so absent/null fields serialize as JSON null.
 *
 * <p>Variants:
 *
 * <ul>
 *   <li>First result accepted: {@code accepted=true, firstResult=true}
 *   <li>Late result logged: {@code accepted=true, firstResult=false, latentlyLogged=true}
 *   <li>Duplicate: {@code accepted=true, firstResult=false, latentlyLogged=true, duplicate=true}
 * </ul>
 *
 * <p>See Story E01S08 AC3, AC4, AC11.
 */
public record SubmitResultResponse(
        boolean accepted,
        boolean firstResult,
        Boolean latentlyLogged,
        Boolean duplicate,
        String deadline) {

    /** Factory: first result accepted (AC3). */
    public static SubmitResultResponse ofFirstResult() {
        return new SubmitResultResponse(true, true, null, null, null);
    }

    /** Factory: late result logged (not a duplicate) (AC4). */
    public static SubmitResultResponse ofLateResult() {
        return new SubmitResultResponse(true, false, true, null, null);
    }

    /** Factory: duplicate idempotent submission (AC11). */
    public static SubmitResultResponse ofDuplicate() {
        return new SubmitResultResponse(true, false, true, true, null);
    }
}
