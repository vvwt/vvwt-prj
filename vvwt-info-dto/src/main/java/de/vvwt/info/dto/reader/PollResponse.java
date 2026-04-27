package de.vvwt.info.dto.reader;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.vvwt.info.dto.event.DomainEvent;
import java.util.List;

/**
 * Response payload for the HTTP poll endpoint {@code GET
 * /api/v1/poll/{tournament_token}/{team_token}} (E38S06 AC3).
 *
 * <p>The response is always envelope-wrapped: {@code Envelope<PollResponse>}.
 *
 * <p>Semantics (per AC3):
 *
 * <ul>
 *   <li>If {@code since == last_applied_seq}: empty {@code deltas} list + current {@code
 *       currentSeq}.
 *   <li>If {@code since < last_applied_seq} and all deltas {@code [since+1..last_applied_seq]} are
 *       retained: delta list + current {@code currentSeq}.
 *   <li>If sequence gap detected OR {@code since < 0}: client must request a full snapshot (server
 *       returns {@code Envelope<TournamentSnapshot>} instead of {@code PollResponse} — caller
 *       discriminates on payload type).
 * </ul>
 *
 * @param deltas ordered list of domain events since the client's known sequence number (may be
 *     empty)
 * @param currentSeq the server's current {@code last_applied_seq} at response time
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC3</a>
 */
public record PollResponse(
        @JsonProperty("deltas") List<DomainEvent> deltas,
        @JsonProperty("current_seq") long currentSeq) {}
