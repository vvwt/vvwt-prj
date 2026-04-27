package de.vvwt.info.reader;

import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.event.DomainEvent;
import de.vvwt.info.dto.reader.PollResponse;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.info.persistence.tournament.TournamentRecord;
import de.vvwt.info.reader.ReaderService.TokenValidationResult;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP short-poll fallback endpoint for the reader stream (E38S06 AC3, AC4, AC7).
 *
 * <p>Endpoint: {@code GET /api/v1/poll/{tournament_token}/{team_token}?since={seq}}
 *
 * <p>Response semantics (AC3):
 *
 * <ul>
 *   <li>{@code since == last_applied_seq} → empty delta list + current seq ({@code
 *       Envelope<PollResponse>})
 *   <li>{@code since < last_applied_seq} AND all deltas {@code [since+1..last_applied_seq]}
 *       retained → delta list + current seq ({@code Envelope<PollResponse>})
 *   <li>Gap or {@code since < 0} → full per-team snapshot ({@code Envelope<TournamentSnapshot>})
 * </ul>
 *
 * <p>Token validation identical to the WS endpoint: invalid/unknown/malformed → 410 Gone (AC9,
 * AC10). Superseded past grace → 410 Gone (AC11).
 *
 * <p>No audit-log writes for reader requests (AC15 — anchored on Brief D-25/S-17).
 *
 * @see <a href="../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC3, AC4,
 *     AC7</a>
 */
@RestController
@RequestMapping("/api/v1/poll")
public class ReaderPollController {

    private final ReaderService readerService;

    public ReaderPollController(ReaderService readerService) {
        this.readerService = readerService;
    }

    /**
     * Returns incremental deltas or a full snapshot depending on the {@code since} parameter.
     *
     * @param tournamentToken opaque tournament bearer token
     * @param teamToken HMAC-derived team bearer token
     * @param since last known sequence number (optional; absent → treat as -1 → full snapshot)
     * @return 200 with {@code Envelope<PollResponse>} or {@code Envelope<TournamentSnapshot>}; 410
     *     Gone for invalid/expired tokens
     */
    @GetMapping("/{tournamentToken}/{teamToken}")
    public ResponseEntity<Envelope<?>> poll(
            @PathVariable("tournamentToken") String tournamentToken,
            @PathVariable("teamToken") String teamToken,
            @RequestParam(value = "since", required = false) Long since) {

        TokenValidationResult result = readerService.validateTokens(tournamentToken, teamToken);

        return switch (result) {
            case TokenValidationResult.Invalid ignored ->
                    ResponseEntity.status(HttpStatus.GONE).build();
            case TokenValidationResult.Superseded ignored ->
                    ResponseEntity.status(HttpStatus.GONE).build();
            case TokenValidationResult.Valid valid -> handleValidRequest(valid, since);
        };
    }

    private ResponseEntity<Envelope<?>> handleValidRequest(
            TokenValidationResult.Valid valid, Long since) {

        TournamentRecord tournament = valid.tournament();
        de.vvwt.info.dto.snapshot.TeamEntry teamEntry = valid.teamEntry();

        // Frozen state: tournament superseded but within grace window → return snapshot with
        // tournament_ended: true. For Phase 1, we return the snapshot only (no tournament_ended
        // field in current DTO — AC5 covers WS; poll returns snapshot for frozen state too).
        // AC3 semantics: gap / since < 0 → full snapshot
        long sinceSeq = (since != null) ? since : -1L;

        if (sinceSeq < 0 || readerService.hasSequenceGap(tournament, sinceSeq)) {
            Optional<TournamentSnapshot> snapshot =
                    readerService.getTeamSnapshot(tournament, teamEntry);
            TournamentSnapshot snapshotValue =
                    snapshot.orElseGet(
                            () ->
                                    new TournamentSnapshot(
                                            tournament.tournamentId(),
                                            tournament.tenantId(),
                                            tournament.lastAppliedSeq(),
                                            List.of(),
                                            List.of(),
                                            false));
            return ResponseEntity.ok(new Envelope<>(Envelope.SCHEMA_VERSION, snapshotValue));
        }

        // sinceSeq >= 0 and no gap: return deltas (may be empty if up-to-date)
        List<DomainEvent> deltas = readerService.getDeltasSince(tournament, sinceSeq);
        PollResponse pollResponse = new PollResponse(deltas, tournament.lastAppliedSeq());
        return ResponseEntity.ok(new Envelope<>(Envelope.SCHEMA_VERSION, pollResponse));
    }
}
