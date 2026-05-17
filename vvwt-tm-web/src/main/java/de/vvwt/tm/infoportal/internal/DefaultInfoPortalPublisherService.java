// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.publish.TournamentRegistrationRequest;
import de.vvwt.info.dto.publish.TournamentRegistrationResponse;
import de.vvwt.info.dto.registration.AlgorithmWarning;
import de.vvwt.info.dto.registration.RegistrationRequest;
import de.vvwt.info.dto.registration.RegistrationResponse;
import de.vvwt.tm.infoportal.Ed25519KeypairManager;
import de.vvwt.tm.infoportal.InfoPortalProperties;
import de.vvwt.tm.infoportal.InfoPortalPublisherService;
import de.vvwt.tm.infoportal.InfoPortalStateDao;
import de.vvwt.tm.infoportal.InfoPortalStateRecord;
import de.vvwt.tm.infoportal.TmJcsCanonicalizer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Default implementation of {@link InfoPortalPublisherService}: TM-side publisher integration
 * service for the Public Participant Info Portal (AC1, AC2, AC3, AC9, AC10, AC11, AC12, AC13).
 *
 * <p>Bean registration is via {@code InfoPortalConfig#infoPortalPublisherService()} — this class
 * carries no {@code @Component} annotation (DEC-70: no test-only or duplicate wiring).
 *
 * @see InfoPortalPublisherService
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">
 *     E38S09</a>
 * @since E57S05 (DEC-58/DEC-72 interface extraction)
 */
public class DefaultInfoPortalPublisherService implements InfoPortalPublisherService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultInfoPortalPublisherService.class);

    private final InfoPortalProperties properties;
    private final InfoPortalStateDao stateDao;
    private final RestTemplate restTemplate;
    private final Ed25519KeypairManager keypairManager;
    private final TmJcsCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;
    private final PublisherStatus status = new PublisherStatus();

    public DefaultInfoPortalPublisherService(
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

    /** {@inheritDoc} */
    @Override
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
                            "Ed25519", publicKeyBase64, null, null); // UNSIGNED (signature=null)
            Envelope<RegistrationRequest> envelope = new Envelope<>(Envelope.SCHEMA_VERSION, req);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Tenant-Id", properties.getTenantId());

            ResponseEntity<Envelope<RegistrationResponse>> resp =
                    restTemplate.exchange(
                            properties.getUrl() + "/api/v1/register",
                            HttpMethod.POST,
                            new HttpEntity<>(envelope, headers),
                            new ParameterizedTypeReference<Envelope<RegistrationResponse>>() {});

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                handleAlgorithmWarning(resp.getBody().payload().algorithm_warning());
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

    /** {@inheritDoc} */
    @Override
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

            ResponseEntity<Envelope<TournamentRegistrationResponse>> resp =
                    restTemplate.exchange(
                            properties.getUrl()
                                    + "/api/v1/tournaments/"
                                    + properties.getTenantId()
                                    + "/"
                                    + properties.getLocationId()
                                    + "/"
                                    + tournamentId
                                    + "/register",
                            HttpMethod.POST,
                            new HttpEntity<>(envelope, headers),
                            new ParameterizedTypeReference<
                                    Envelope<TournamentRegistrationResponse>>() {});

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                TournamentRegistrationResponse tResp = resp.getBody().payload();
                stateDao.upsertRegistration(
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

    /** {@inheritDoc} */
    @Override
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

    /** {@inheritDoc} */
    @Override
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

    /** {@inheritDoc} */
    @Override
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

    /** {@inheritDoc} */
    @Override
    public PublisherStatus getStatus() {
        return status;
    }
}
