package de.vvwt.info.reader.internal;

import de.vvwt.info.reader.ReaderService;
import de.vvwt.info.reader.ReaderService.TokenValidationResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriTemplate;

/**
 * Validates tournament and team tokens BEFORE the WebSocket handshake completes (E38S06 AC9, AC10).
 *
 * <p>The interceptor extracts {@code {tournament_token}} and {@code {team_token}} from the request
 * URI path, invokes {@link ReaderService#validateTokens}, and either:
 *
 * <ul>
 *   <li>Allows the handshake to proceed (valid tokens) — stores the resolved tournament record and
 *       team entry in the WS session attributes.
 *   <li>Rejects the handshake with HTTP 410 Gone (invalid, malformed, or superseded tokens).
 * </ul>
 *
 * <p>Uniform 410 for all rejection cases — no information leakage about which check failed (AC10).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC9,
 *     AC10</a>
 */
public class ReaderHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ReaderHandshakeInterceptor.class);

    private static final UriTemplate URI_TEMPLATE =
            new UriTemplate("/api/v1/stream/{tournament_token}/{team_token}");

    private final ReaderService readerService;

    public ReaderHandshakeInterceptor(ReaderService readerService) {
        this.readerService = readerService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        String path = request.getURI().getPath();
        Map<String, String> vars = URI_TEMPLATE.match(path);

        String tournamentToken = vars.get("tournament_token");
        String teamToken = vars.get("team_token");

        if (tournamentToken == null || teamToken == null) {
            response.setStatusCode(HttpStatus.GONE);
            return false;
        }

        TokenValidationResult result = readerService.validateTokens(tournamentToken, teamToken);

        return switch (result) {
            case TokenValidationResult.Valid valid -> {
                attributes.put(ReaderWebSocketHandler.ATTR_TOURNAMENT_RECORD, valid.tournament());
                attributes.put(ReaderWebSocketHandler.ATTR_TEAM_ENTRY, valid.teamEntry());
                yield true;
            }
            case TokenValidationResult.Superseded ignored -> {
                response.setStatusCode(HttpStatus.GONE);
                yield false;
            }
            case TokenValidationResult.Invalid ignored -> {
                response.setStatusCode(HttpStatus.GONE);
                yield false;
            }
        };
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No post-handshake action needed
    }
}
