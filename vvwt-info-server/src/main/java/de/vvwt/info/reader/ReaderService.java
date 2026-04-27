package de.vvwt.info.reader;

import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.info.persistence.tournament.TournamentRecord;
import java.util.List;
import java.util.Optional;

/**
 * Service interface for reader endpoint operations (E38S06).
 *
 * <p>Provides token validation, snapshot retrieval, delta retrieval, and per-team view projection
 * for the WebSocket and HTTP poll reader endpoints.
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S06.story.md">E38S06</a>
 */
public interface ReaderService {

    /**
     * Result of a token validation attempt.
     *
     * <p>Sealed hierarchy: exactly three outcomes.
     */
    sealed interface TokenValidationResult
            permits TokenValidationResult.Valid,
                    TokenValidationResult.Superseded,
                    TokenValidationResult.Invalid {

        /**
         * Valid — tokens match; tournament is active or within 24h grace.
         *
         * @param tournament the matched tournament record
         * @param teamEntry the team identified by the token pair
         * @param withinGrace {@code true} if tournament is superseded but within the 24h grace
         *     window
         */
        record Valid(TournamentRecord tournament, TeamEntry teamEntry, boolean withinGrace)
                implements TokenValidationResult {}

        /** Superseded — tournament is past the 24h grace window; return 410 Gone. */
        record Superseded() implements TokenValidationResult {}

        /** Invalid — tournament not found, token malformed, or HMAC mismatch; return 410 Gone. */
        record Invalid() implements TokenValidationResult {}
    }

    /**
     * Validates the URL token pair and resolves tournament + team.
     *
     * <p>Per AC9, AC10: all invalid/unknown/malformed token combinations return {@link
     * TokenValidationResult.Invalid} — never 404.
     *
     * @param tournamentToken the URL-path tournament token
     * @param teamToken the URL-path team token (Base64URL HMAC)
     * @return the validation result
     */
    TokenValidationResult validateTokens(String tournamentToken, String teamToken);

    /**
     * Returns the per-team snapshot for the given tournament, filtered to the requesting team's
     * scope (AC12).
     *
     * @param tournament the tournament record
     * @param teamEntry the requesting team entry
     * @return the filtered per-team snapshot, or empty if state is null (no snapshot yet)
     */
    Optional<TournamentSnapshot> getTeamSnapshot(TournamentRecord tournament, TeamEntry teamEntry);

    /**
     * Returns incremental delta events for the given tournament since {@code sinceSeq}.
     *
     * <p>Returns empty if no deltas are available since {@code sinceSeq}. Callers must check for
     * gaps using {@link #hasSequenceGap(TournamentRecord, long)} before calling this.
     *
     * @param tournament the tournament record
     * @param sinceSeq lower bound (exclusive)
     * @return list of domain events since {@code sinceSeq}
     */
    List<de.vvwt.info.dto.event.DomainEvent> getDeltasSince(
            TournamentRecord tournament, long sinceSeq);

    /**
     * Returns {@code true} if there is a sequence gap between {@code sinceSeq+1} and the oldest
     * retained delta (i.e., the client's position is beyond the ring-buffer retention window).
     *
     * @param tournament the tournament record
     * @param sinceSeq the client's last known sequence number
     * @return {@code true} if the client should request a full snapshot (AC4)
     */
    boolean hasSequenceGap(TournamentRecord tournament, long sinceSeq);
}
