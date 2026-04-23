package de.vvwt.tm.photo.internal;

import de.vvwt.tm.photo.PhotoUrlBuilder;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link PhotoUrlBuilder} (E23S02, DEC-35 naming canon).
 *
 * <p>Emits the OLD URL pattern during the parallel phase (E23S01–E23S04): {@code
 * /api/tournaments/{tournamentId}/teams/{teamId}/photo} — byte-identical to the hardcoded string
 * formerly in {@code CertificateAssembler.java:377}. At E23S05 Cutover-1, the URL template constant
 * is updated atomically in this class (single-file change, no consumer rewire needed).
 *
 * <p>Null-argument handling: {@link Objects#requireNonNull} is applied to both parameters,
 * producing a {@link NullPointerException} on invalid input. Rationale: canonical Java port
 * contract — fail-fast on invalid input; defensive empty-string returns would hide programmer
 * errors at call sites (AC-ERROR-HANDLING).
 *
 * <p>Authorizing decisions: DEC-35 (implementation in {@code .internal}; {@code Default*Service}
 * naming canon), DEC-22 (TDD Iron Law — written after RED-first test in E23S02).
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
        return "/api/tournaments/" + tournamentId + "/teams/" + teamId + "/photo";
    }
}
