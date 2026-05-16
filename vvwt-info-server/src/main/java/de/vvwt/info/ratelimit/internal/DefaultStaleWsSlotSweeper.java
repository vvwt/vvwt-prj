package de.vvwt.info.ratelimit.internal;

import de.vvwt.info.ratelimit.StaleWsSlotSweeper;
import de.vvwt.info.ratelimit.TournamentConcurrencyLimiter;
import de.vvwt.info.reader.ReaderSessionRegistry;
import de.vvwt.info.reader.internal.ReaderWebSocketHandler;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Scheduled sweeper that releases stale per-tournament-token concurrency slots for WS sessions that
 * closed without triggering the normal handler callbacks (E38S07 AC13 — abrupt-close fallback).
 *
 * <p>Primary slot release happens in {@link
 * de.vvwt.info.reader.internal.ReaderWebSocketHandler#afterConnectionClosed} (clean close) and
 * {@link de.vvwt.info.reader.internal.ReaderWebSocketHandler#handleTransportError} (transport
 * error). This sweeper is a safety-net for sessions where neither callback fired — e.g., prolonged
 * half-open TCP connections that survive the server-side idle timeout.
 *
 * <p>Every {@code vvwt.info.rate-limit.stale-ws-sweep-interval-ms} milliseconds (default: 30 000),
 * the sweeper walks all sessions in {@link ReaderSessionRegistry}. Any session that is no longer
 * open has its tournament-token slot released via {@link TournamentConcurrencyLimiter#releaseSlot}.
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC13</a>
 */
@Component
public class DefaultStaleWsSlotSweeper implements StaleWsSlotSweeper {

    private static final Logger log = LoggerFactory.getLogger(DefaultStaleWsSlotSweeper.class);

    private final TournamentConcurrencyLimiter concurrencyLimiter;
    private final ReaderSessionRegistry sessionRegistry;

    public DefaultStaleWsSlotSweeper(
            TournamentConcurrencyLimiter concurrencyLimiter,
            ReaderSessionRegistry sessionRegistry) {
        this.concurrencyLimiter = concurrencyLimiter;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Releases slots for any WS sessions that are no longer open.
     *
     * <p>Scheduled every {@code vvwt.info.rate-limit.stale-ws-sweep-interval-ms} ms (default 30s).
     * Iterates all tracked sessions; any session whose {@link WebSocketSession#isOpen()} returns
     * {@code false} has its tournament-token slot released. The session will be deregistered by the
     * next handler callback or on the following sweep.
     */
    @Override
    @Scheduled(fixedDelayString = "${vvwt.info.rate-limit.stale-ws-sweep-interval-ms:30000}")
    public void sweepStaleSlots() {
        Set<WebSocketSession> sessions = sessionRegistry.allSessions();
        int released = 0;
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                String tournamentToken =
                        (String)
                                session.getAttributes()
                                        .get(ReaderWebSocketHandler.ATTR_TOURNAMENT_TOKEN);
                if (tournamentToken != null) {
                    concurrencyLimiter.releaseSlot(tournamentToken);
                    released++;
                    log.debug(
                            "Stale-sweep: released slot for tournament-token {} (session {})",
                            tournamentToken,
                            session.getId());
                }
            }
        }
        if (released > 0) {
            log.info("Stale-sweep: released {} stale WS slot(s)", released);
        }
    }
}
