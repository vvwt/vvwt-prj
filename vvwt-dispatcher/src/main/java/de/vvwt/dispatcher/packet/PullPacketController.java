package de.vvwt.dispatcher.packet;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.dispatcher.audit.AuditEntry;
import de.vvwt.dispatcher.audit.AuditService;
import de.vvwt.worker.types.CanonicalPhaseDef;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for {@code POST /pull-packet} (E01S07 AC3–AC11).
 *
 * <h2>Responses</h2>
 * <ul>
 *   <li>{@code 200 OK} — packet assigned; body is {@link PullPacketResponse}</li>
 *   <li>{@code 204 No Content} — no pending packets available; worker should back off (AC6)</li>
 *   <li>{@code 400 Bad Request} — malformed request body (AC9)</li>
 *   <li>{@code 401 Unauthorized} — authentication failure (unknown key, bad sig, stale nonce) (AC4, AC9)</li>
 *   <li>{@code 403 Forbidden} — non-worker key (submitter calling pull-packet) (AC4)</li>
 *   <li>{@code 500 Internal Server Error} — unexpected error</li>
 * </ul>
 *
 * <p>Every call is audit-logged via {@link AuditService}: (timestamp, sourceIP, workerKeyId,
 * outcome, httpStatus). The {@code packetId} is additionally logged at INFO level (AC10).
 *
 * <p>See Story E01S07 AC3–AC11 and DEC-6.
 */
@RestController
public class PullPacketController {

    private static final Logger log = LoggerFactory.getLogger(PullPacketController.class);
    private static final String ENDPOINT = "/pull-packet";

    private final PullPacketService pullPacketService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public PullPacketController(PullPacketService pullPacketService,
                                AuditService auditService,
                                ObjectMapper objectMapper) {
        this.pullPacketService = pullPacketService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Authenticates a worker and assigns the next pending packet.
     *
     * @param request  parsed JSON request body
     * @param httpReq  HTTP request (source IP for audit logging)
     * @return 200 with packet details, 204 if no work, or an error response
     */
    @PostMapping("/pull-packet")
    public ResponseEntity<?> pullPacket(
            @RequestBody PullPacketRequest request,
            HttpServletRequest httpReq) {

        try {
            PullPacketService.PullResult result =
                    pullPacketService.execute(request.workerKeyId(), request.signature(), request.signedNonce());

            return switch (result) {
                case PullPacketService.PullResult.Assigned assigned -> {
                    PacketRecord packet = assigned.packet();
                    CanonicalPhaseDef jobDef = parseCanonicalPhaseDef(assigned.job().getCanonicalPhaseDefJson());
                    PullPacketResponse body = new PullPacketResponse(
                            packet.getPacketId(),
                            packet.getJobId(),
                            jobDef,
                            packet.getRankFrom(),
                            packet.getRankTo(),
                            assigned.deadline());

                    auditService.log(httpReq, ENDPOINT, request.workerKeyId(),
                            AuditEntry.OUTCOME_VERIFIED, HttpStatus.OK.value());
                    log.info("pull-packet success: workerKeyId={} packetId={} jobId={}",
                            request.workerKeyId(), packet.getPacketId(), packet.getJobId());
                    yield ResponseEntity.ok(body);
                }
                case PullPacketService.PullResult.NoWork ignored -> {
                    auditService.log(httpReq, ENDPOINT, request.workerKeyId(),
                            AuditEntry.OUTCOME_VERIFIED, HttpStatus.NO_CONTENT.value());
                    log.debug("pull-packet no-work: workerKeyId={}", request.workerKeyId());
                    yield ResponseEntity.noContent().build();
                }
            };

        } catch (IllegalArgumentException badRequest) {
            auditService.log(httpReq, ENDPOINT, request.workerKeyId(),
                    AuditEntry.OUTCOME_NA, HttpStatus.BAD_REQUEST.value());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", badRequest.getMessage()));

        } catch (PullPacketService.UnauthorizedException unauthorized) {
            auditService.log(httpReq, ENDPOINT, request.workerKeyId(),
                    AuditEntry.OUTCOME_FAILED, HttpStatus.UNAUTHORIZED.value());
            log.warn("pull-packet unauthorized: workerKeyId={} error={} reason={}",
                    request.workerKeyId(), unauthorized.getErrorCode(), unauthorized.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", unauthorized.getErrorCode()));

        } catch (PullPacketService.ForbiddenException forbidden) {
            auditService.log(httpReq, ENDPOINT, request.workerKeyId(),
                    AuditEntry.OUTCOME_FAILED, HttpStatus.FORBIDDEN.value());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "forbidden",
                                 "message", "Only worker keys may call pull-packet"));

        } catch (Exception unexpected) {
            log.error("INCIDENT: Unexpected error on pull-packet for workerKeyId={}",
                    request.workerKeyId(), unexpected);
            auditService.log(httpReq, ENDPOINT, request.workerKeyId(),
                    AuditEntry.OUTCOME_NA, HttpStatus.INTERNAL_SERVER_ERROR.value());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "internal server error"));
        }
    }

    private CanonicalPhaseDef parseCanonicalPhaseDef(String json) {
        try {
            return objectMapper.readValue(json, CanonicalPhaseDef.class);
        } catch (Exception parseError) {
            throw new IllegalStateException(
                    "Failed to parse canonicalPhaseDefJson for pull-packet response: "
                    + parseError.getMessage(), parseError);
        }
    }
}
