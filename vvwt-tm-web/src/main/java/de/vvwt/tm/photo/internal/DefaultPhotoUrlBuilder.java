package de.vvwt.tm.photo.internal;

import de.vvwt.tm.photo.PhotoUrlBuilder;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link PhotoUrlBuilder} (E23S02, DEC-35 naming canon).
 *
 * <p>Emits the NEW URL pattern as of E23S05 Cutover-1: {@code
 * /api/photo/tournaments/{tournamentId}/teams/{teamId}}. Renamed atomically at Cutover-1 per DEC-21
 * per-context atomic cutover and DEC-40 precedent (E22S08 URL-rename pattern). The RED-GREEN pair
 * in this commit: test assertions updated first (RED against old impl), then this impl updated
 * (GREEN).
 *
 * <p>Null-argument handling: {@link Objects#requireNonNull} is applied to both parameters,
 * producing a {@link NullPointerException} on invalid input. Rationale: canonical Java port
 * contract — fail-fast on invalid input; defensive empty-string returns would hide programmer
 * errors at call sites (AC-ERROR-HANDLING).
 *
 * <p>Authorizing decisions: DEC-35 (implementation in {@code .internal}; {@code Default*Service}
 * naming canon), DEC-22 (TDD Iron Law — written after RED-first test in E23S02), DEC-21
 * (per-context atomic cutover), DEC-40 (URL-rename precedent E22S08).
 *
 * @see PhotoUrlBuilder
 * @since E23S02
 */
@Component
public class DefaultPhotoUrlBuilder implements PhotoUrlBuilder {

    @Override
    public String buildTeamPhotoUrl(UUID tournamentId, UUID teamId) {
        Objects.requireNonNull(tournamentId, "tournamentId must not be null");
        Objects.requireNonNull(teamId, "teamId must not be null");
        return "/api/photo/tournaments/" + tournamentId + "/teams/" + teamId;
    }
}
