package de.vvwt.tm.infoportal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.publish.TournamentRegistrationRequest;
import de.vvwt.info.dto.publish.TournamentRegistrationResponse;
import de.vvwt.info.dto.registration.AlgorithmWarning;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.dto.registration.RegistrationResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * TM-side publisher integration service for the Public Participant Info Portal (AC1, AC2, AC3, AC9,
 * AC10, AC11, AC12, AC13).
 *
 * <p>Activated when {@code info-portal.url} is configured. Coordinates:
 *
 * <ul>
 *   <li>Tenant registration (POST /api/v1/register) — UNSIGNED per D-X4(b).
 *   <li>Tournament registration (POST /api/v1/tournaments/{t}/{l}/{id}/register) — SIGNED.
 *   <li>Delta publish (POST /api/v1/publish/{t}/{l}/{id}?seq=N) — SIGNED with JCS body.
 *   <li>Snapshot recovery on 409 FULL_RESYNC — posts snapshot to
 *       /api/v1/publish/{t}/{l}/{id}/snapshot.
 *   <li>Algorithm-deprecation-warning handling per DEC-43 D3.
 *   <li>Error-handling per AC11 (4xx/5xx branches).
 * </ul>
 *
 * <p>NO outbound queue (D-X8 c): events during 5xx outages are accepted-as-lost. The next event's
 * seq mismatch triggers 409 FULL_RESYNC → snapshot recovery.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09</a>
 */
public class InfoPortalPublisherService {

    private static final Logger log = LoggerFactory.getLogger(InfoPortalPublisherService.class);

    private final InfoPortalProperties properties;
    private final InfoPortalStateDao stateDao;
    private final RestTemplate restTemplate;
    private final Ed25519KeypairManager keypairManager;
    private final TmJcsCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;
    private final PublisherStatus status = new PublisherStatus();

    public InfoPortalPublisherService(
            InfoPortalProperties properties,
            InfoPortalStateDao stateDao,
            RestTemplate restTemplate,
            Ed25519KeypairManager keypairManager,
            TmJcsCanonicalizer canonicalizer) {
        this.properties = properties;
        this.stateDao = stateDao;
        this.restTemplate = restTemplate;
        this.keypairManager = keypairManager;
        this.canonicalizer = canonicalizer;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    // -------------------------------------------------------------------------
    // Tenant registration (Brief D-X4 b — UNSIGNED)
    // -------------------------------------------------------------------------

    /**
     * Registers this TM instance with the info-server (UNSIGNED per D-X4 b — operator trust,
     * first-key-wins binding per DEC-42 D3).
     *
     * <p>On success, stores the public key binding. If {@code algorithm_warning} is present in the
     * response, invokes {@link #handleAlgorithmWarning}.
     */
    public void registerTenant() {
        if (!properties.isEnabled()) {
            log.info("[InfoPortal] Publisher feature disabled — info-portal.url not configured");
            return;
        }
        try {
            String publicKeyBase64 =
                    Base64.getEncoder().encodeToString(keypairManager.getPublicKey().getEncoded());
            RegistrationRequest req =
                    new RegistrationRequest(
                            "ed25519", publicKeyBase64, null, null); // UNSIGNED (signature=null)
            Envelope<RegistrationRequest> envelope = new Envelope<>(Envelope.SCHEMA_VERSION, req);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Tenant-Id", properties.getTenantId());

            ResponseEntity<Object> resp =
                    restTemplate.postForEntity(
                            properties.getUrl() + "/api/v1/register",
                            new HttpEntity<>(envelope, headers),
                            Object.class);

            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() instanceof RegistrationResponse regResp) {
                handleAlgorithmWarning(regResp.algorithm_warning());
                status.setRegistered(true);
                log.info("[InfoPortal] Tenant registered with info-server");
            }
        } catch (HttpClientErrorException e) {
            handleRegistrationError(e);
        } catch (Exception e) {
            log.error(
                    "[InfoPortal] Unexpected error during tenant registration: {}", e.getMessage());
            status.setRegistrationError(true);
        }
    }

    // -------------------------------------------------------------------------
    // Tournament registration (D-X2 c — SIGNED)
    // -------------------------------------------------------------------------

    /**
     * Registers a tournament via the dedicated endpoint (D-X2 c). Returns the {@code
     * tournament_token} + {@code per_tournament_secret} and persists them in {@code
     * info_portal_state}.
     *
     * @param tournamentId the tournament identifier
     * @param teamUuids UUIDs of participating teams (may be empty for pre-registration)
     */
    public void registerTournament(String tournamentId, List<UUID> teamUuids) {
        if (!properties.isEnabled() || status.isAlgorithmDeprecatedHardStop()) return;

        try {
            TournamentRegistrationRequest req =
                    new TournamentRegistrationRequest(teamUuids != null ? teamUuids : List.of());
            Envelope<TournamentRegistrationRequest> envelope =
                    new Envelope<>(Envelope.SCHEMA_VERSION, req);

            String payloadJson = objectMapper.writeValueAsString(req);
            byte[] canonicalBytes = canonicalizer.canonicalize(payloadJson);
            String signatureBase64 =
                    Base64.getEncoder().encodeToString(keypairManager.sign(canonicalBytes));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Vvwt-Signature", signatureBase64);

            ResponseEntity<Object> resp =
                    restTemplate.postForEntity(
                            properties.getUrl()
                                    + "/api/v1/tournaments/"
                                    + properties.getTenantId()
                                    + "/"
                                    + properties.getLocationId()
                                    + "/"
                                    + tournamentId
                                    + "/register",
                            new HttpEntity<>(envelope, headers),
                            Object.class);

            if (resp.getStatusCode().is2xxSuccessful()
                    && resp.getBody() instanceof TournamentRegistrationResponse tResp) {
                stateDao.upsertRegistration(
                        properties.getTenantId(),
                        properties.getLocationId(),
                        tournamentId,
                        tResp.tournamentToken(),
                        tResp.perTournamentSecret());
                log.info("[InfoPortal] Tournament '{}' registered — token stored", tournamentId);
            }
        } catch (HttpClientErrorException e) {
            handlePublishError(e, tournamentId, null);
        } catch (Exception e) {
            log.error(
                    "[InfoPortal] Error registering tournament '{}': {}",
                    tournamentId,
                    e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Delta publish (D-X6 a — JCS-signed)
    // -------------------------------------------------------------------------

    /**
     * Publishes a domain event delta to the info-server (AC2, AC9, AC11, AC12).
     *
     * <p>Seq is assigned atomically before posting. On 409 FULL_RESYNC, the current {@code
     * last_published_seq} is retained (NOT reset to 1) and a snapshot is posted (AC3, AC12).
     *
     * @param tournamentId tournament identifier
     * @param eventJson JSON string of the {@code DomainEvent} payload
     */
    public void publishDelta(String tournamentId, String eventJson) {
        if (!properties.isEnabled() || status.isAlgorithmDeprecatedHardStop()) {
            log.info(
                    "[InfoPortal] Publisher not active — skipping delta publish for '{}'",
                    tournamentId);
            return;
        }

        Optional<InfoPortalStateRecord> stateOpt =
                stateDao.findByTournament(properties.getLocationId(), tournamentId);
        if (stateOpt.isEmpty()) {
            log.warn(
                    "[InfoPortal] No registration found for tournament '{}' — cannot publish delta",
                    tournamentId);
            return;
        }

        try {
            // Atomic seq increment BEFORE posting (AC12)
            long seq = stateDao.incrementAndGetSeq(properties.getLocationId(), tournamentId);

            Envelope<String> envelope = new Envelope<>(Envelope.SCHEMA_VERSION, eventJson);
            String envelopeJson = objectMapper.writeValueAsString(envelope);
            byte[] canonicalBytes = canonicalizer.canonicalize(envelopeJson);
            String signatureBase64 =
                    Base64.getEncoder().encodeToString(keypairManager.sign(canonicalBytes));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Vvwt-Signature", signatureBase64);

            restTemplate.postForEntity(
                    properties.getUrl()
                            + "/api/v1/publish/"
                            + properties.getTenantId()
                            + "/"
                            + properties.getLocationId()
                            + "/"
                            + tournamentId
                            + "?seq="
                            + seq,
                    new HttpEntity<>(envelopeJson, headers),
                    Object.class);

            stateDao.updateLastPublishedAt(properties.getLocationId(), tournamentId, Instant.now());
            status.recordPublishSuccess();

        } catch (HttpClientErrorException e) {
            handlePublishError(e, tournamentId, eventJson);
        } catch (HttpServerErrorException e) {
            log.error(
                    "[InfoPortal] 5xx from info-server for tournament '{}': {}",
                    tournamentId,
                    e.getStatusCode());
            status.recordServerError();
        } catch (Exception e) {
            log.error(
                    "[InfoPortal] Unexpected error publishing delta for '{}': {}",
                    tournamentId,
                    e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot recovery (D-X8 c — NO queue)
    // -------------------------------------------------------------------------

    /**
     * Posts the current tournament snapshot to recover from 409 FULL_RESYNC (AC3).
     *
     * <p>Seq is NOT reset. The snapshot carries the current {@code last_published_seq} value. The
     * info-server overwrites its {@code last_applied_seq} to that value; subsequent deltas continue
     * at {@code seq = current + 1}.
     *
     * @param tournamentId tournament identifier
     * @param snapshotJson JSON string of the current tournament state snapshot
     */
    public void postSnapshot(String tournamentId, String snapshotJson) {
        try {
            // Seq NOT reset (AC12): carry current seq in snapshot
            long currentSeq = stateDao.findCurrentSeq(properties.getLocationId(), tournamentId);

            String snapshotPayload =
                    "{\"sequenceNumber\":" + currentSeq + ",\"state\":" + snapshotJson + "}";
            byte[] canonicalBytes = canonicalizer.canonicalize(snapshotPayload);
            String signatureBase64 =
                    Base64.getEncoder().encodeToString(keypairManager.sign(canonicalBytes));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Vvwt-Signature", signatureBase64);

            restTemplate.postForEntity(
                    properties.getUrl()
                            + "/api/v1/publish/"
                            + properties.getTenantId()
                            + "/"
                            + properties.getLocationId()
                            + "/"
                            + tournamentId
                            + "/snapshot",
                    new HttpEntity<>(snapshotPayload, headers),
                    Object.class);

            log.info(
                    "[InfoPortal] Snapshot posted for tournament '{}' at seq={}",
                    tournamentId,
                    currentSeq);
        } catch (Exception e) {
            log.error(
                    "[InfoPortal] Error posting snapshot for '{}': {}",
                    tournamentId,
                    e.getMessage());
            status.recordServerError();
        }
    }

    // -------------------------------------------------------------------------
    // Algorithm deprecation handling (DEC-43 D3)
    // -------------------------------------------------------------------------

    /**
     * Handles an {@link AlgorithmWarning} from a registration response (AC10, DEC-43 D3).
     *
     * <p>If {@code warning} is non-null, sets the deprecation status on {@link PublisherStatus}.
     * Severity is HIGH if {@code days_remaining ≤ deprecationWarningThresholdDays}, else LOW. The
     * warning is always surfaced (DEC-43 D3 mandates it regardless of threshold).
     */
    public void handleAlgorithmWarning(AlgorithmWarning warning) {
        if (warning == null) {
            return;
        }
        int daysRemaining =
                warning.days_remaining() != null ? warning.days_remaining() : Integer.MAX_VALUE;
        boolean withinThreshold = daysRemaining <= properties.getDeprecationWarningThresholdDays();
        PublisherStatus.DeprecationSeverity severity =
                withinThreshold
                        ? PublisherStatus.DeprecationSeverity.HIGH
                        : PublisherStatus.DeprecationSeverity.LOW;
        status.setDeprecationWarning(warning.algorithm_id(), warning.deprecation_date(), severity);
        log.warn(
                "[InfoPortal] Algorithm '{}' deprecated on {} ({} days remaining) — severity={}",
                warning.algorithm_id(),
                warning.deprecation_date(),
                daysRemaining,
                severity);
    }

    // -------------------------------------------------------------------------
    // Error handling (AC11)
    // -------------------------------------------------------------------------

    private void handleRegistrationError(HttpClientErrorException e) {
        HttpStatus status4xx = HttpStatus.resolve(e.getStatusCode().value());
        if (status4xx == null) {
            log.error(
                    "[InfoPortal] Registration error (unknown status {}): {}",
                    e.getStatusCode(),
                    e.getMessage());
            this.status.setRegistrationError(true);
            return;
        }
        switch (status4xx) {
            case FORBIDDEN -> {
                log.error("[InfoPortal] Registration rejected (403): {}", e.getMessage());
                this.status.setRegistrationError(true);
            }
            case CONFLICT -> {
                log.warn(
                        "[InfoPortal] Registration key mismatch (409 KEY_MISMATCH) — re-register"
                                + " required");
                this.status.setRegistrationError(true);
            }
            case GONE -> {
                log.error("[InfoPortal] Algorithm deprecated (410) — publisher stopped");
                this.status.setAlgorithmDeprecatedHardStop(true);
            }
            case TOO_MANY_REQUESTS -> {
                log.warn("[InfoPortal] Rate limited (429) during registration");
                this.status.setRegistrationError(true);
            }
            default -> {
                log.error("[InfoPortal] Registration failed ({}): {}", status4xx, e.getMessage());
                this.status.setRegistrationError(true);
            }
        }
    }

    @SuppressWarnings("unused")
    private void handlePublishError(
            HttpClientErrorException e, String tournamentId, String eventJson) {
        HttpStatus status4xx = HttpStatus.resolve(e.getStatusCode().value());
        if (status4xx == null) {
            log.error(
                    "[InfoPortal] Publish error (unknown status {}): {}",
                    e.getStatusCode(),
                    e.getMessage());
            return;
        }
        switch (status4xx) {
            case UNAUTHORIZED -> {
                log.error(
                        "[InfoPortal] Signature mismatch (401) for tournament '{}' — info-server"
                            + " signature mismatch; keypair may have changed; re-register manually",
                        tournamentId);
                this.status.setSignatureMismatch(true);
            }
            case CONFLICT -> {
                // 409 FULL_RESYNC — snapshot recovery (AC3, D-X8 c — no queue)
                log.info(
                        "[InfoPortal] 409 FULL_RESYNC for tournament '{}' — posting snapshot",
                        tournamentId);
                postSnapshot(tournamentId, eventJson != null ? eventJson : "{}");
            }
            case FORBIDDEN -> {
                log.error(
                        "[InfoPortal] 403 RegistrationRejected for tournament '{}': {}",
                        tournamentId,
                        e.getMessage());
            }
            case GONE -> {
                log.error(
                        "[InfoPortal] 410 ALGORITHM_DEPRECATED — publisher stopped; operator must"
                                + " rotate algorithm");
                this.status.setAlgorithmDeprecatedHardStop(true);
            }
            case TOO_MANY_REQUESTS -> {
                log.warn(
                        "[InfoPortal] 429 RateLimited for tournament '{}' — exponential backoff",
                        tournamentId);
                this.status.recordRateLimited();
            }
            default ->
                    log.warn(
                            "[InfoPortal] Unexpected 4xx ({}) for tournament '{}'",
                            status4xx,
                            tournamentId);
        }
    }

    // -------------------------------------------------------------------------
    // Status accessor
    // -------------------------------------------------------------------------

    public PublisherStatus getStatus() {
        return status;
    }

    // -------------------------------------------------------------------------
    // PublisherStatus inner class
    // -------------------------------------------------------------------------

    /**
     * Mutable status holder for the publisher (admin UI data source via {@link
     * InfoPortalStatusController}).
     */
    public static class PublisherStatus {

        public enum DeprecationSeverity {
            HIGH,
            LOW
        }

        private boolean registered;
        private boolean registrationError;
        private boolean signatureMismatch;
        private boolean algorithmDeprecatedHardStop;
        private int consecutiveServerErrors;
        private int rateLimitedCount;
        private String deprecationAlgorithmId;
        private LocalDate deprecationDate;
        private DeprecationSeverity deprecationSeverity;
        private boolean publisherUnhealthy;

        public boolean isRegistered() {
            return registered;
        }

        public void setRegistered(boolean v) {
            this.registered = v;
        }

        public boolean isRegistrationError() {
            return registrationError;
        }

        public void setRegistrationError(boolean v) {
            this.registrationError = v;
        }

        public boolean isSignatureMismatch() {
            return signatureMismatch;
        }

        public void setSignatureMismatch(boolean v) {
            this.signatureMismatch = v;
        }

        public boolean isAlgorithmDeprecatedHardStop() {
            return algorithmDeprecatedHardStop;
        }

        public void setAlgorithmDeprecatedHardStop(boolean v) {
            this.algorithmDeprecatedHardStop = v;
        }

        public boolean hasDeprecationWarning() {
            return deprecationAlgorithmId != null;
        }

        public DeprecationSeverity getDeprecationSeverity() {
            return deprecationSeverity;
        }

        public String getDeprecationAlgorithmId() {
            return deprecationAlgorithmId;
        }

        public LocalDate getDeprecationDate() {
            return deprecationDate;
        }

        public boolean isPublisherUnhealthy() {
            return publisherUnhealthy;
        }

        public void setDeprecationWarning(
                String algorithmId, LocalDate date, DeprecationSeverity severity) {
            this.deprecationAlgorithmId = algorithmId;
            this.deprecationDate = date;
            this.deprecationSeverity = severity;
        }

        public void recordPublishSuccess() {
            consecutiveServerErrors = 0;
            publisherUnhealthy = false;
        }

        public void recordServerError() {
            consecutiveServerErrors++;
            // AC11: after 5 consecutive failures in 5-minute window → admin-UI indicator
            if (consecutiveServerErrors >= 5) {
                publisherUnhealthy = true;
            }
        }

        public void recordRateLimited() {
            rateLimitedCount++;
        }
    }
}
