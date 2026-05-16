package de.vvwt.tm.infoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.registration.AlgorithmWarning;
import de.vvwt.info.dto.registration.RegistrationResponse;
import de.vvwt.tm.infoportal.InfoPortalPublisherService.PublisherStatus;
import de.vvwt.tm.infoportal.internal.DefaultInfoPortalPublisherService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Unit tests for {@link InfoPortalPublisherService} (AC1 — DEC-22 RED-first for publisher service
 * classes; AC11 — 4xx/5xx error handling).
 *
 * <p>DEC-22 Iron Law: written RED-first before production class exists.
 *
 * <p>Tests in a different package from subject (this is in {@code infoportal} test package, same as
 * subject's production package) — same-package test, may white-box per DEC-36.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC1, AC11</a>
 */
class InfoPortalPublisherServiceTest {

    private InfoPortalStateDao stateDao;
    private RestTemplate restTemplate;
    private Ed25519KeypairManager keypairManager;
    private TmJcsCanonicalizer canonicalizer;
    private InfoPortalProperties properties;
    private InfoPortalPublisherService service;

    @BeforeEach
    void setUp() throws Exception {
        stateDao = mock(InfoPortalStateDao.class);
        restTemplate = mock(RestTemplate.class);
        keypairManager = mock(Ed25519KeypairManager.class);
        canonicalizer = mock(TmJcsCanonicalizer.class);
        properties = new InfoPortalProperties();
        properties.setUrl("https://info.example.com");
        properties.setLocationId("venue-1");
        properties.setDeprecationWarningThresholdDays(30);

        // Mock keypair
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        when(keypairManager.getPublicKey()).thenReturn(kp.getPublic());
        when(keypairManager.sign(any())).thenReturn(new byte[64]);
        when(canonicalizer.canonicalize(anyString())).thenReturn("{}".getBytes());

        service =
                new DefaultInfoPortalPublisherService(
                        properties, stateDao, restTemplate, keypairManager, canonicalizer);
    }

    // -------------------------------------------------------------------------
    // AC1 — service class existence (DEC-22 RED-first demonstration)
    // -------------------------------------------------------------------------

    @Test
    void serviceCanBeInstantiated() {
        assertThat(service).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Tenant registration (D-X4 b — first registration UNSIGNED)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void registerTenant_callsRegistrationEndpointWithoutSignature() throws Exception {
        Envelope<RegistrationResponse> envResp =
                new Envelope<>(Envelope.SCHEMA_VERSION, new RegistrationResponse(null, null));
        when(restTemplate.exchange(
                        anyString(),
                        eq(HttpMethod.POST),
                        any(),
                        any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(envResp));

        service.registerTenant();

        // Verify call was made via exchange (UNSIGNED registration — no signature in body per D-X4
        // b)
        verify(restTemplate, times(1))
                .exchange(
                        anyString(),
                        eq(HttpMethod.POST),
                        any(),
                        any(ParameterizedTypeReference.class));
        // Signature should NOT be called for tenant registration (unsigned)
        verify(keypairManager, never()).sign(any());
    }

    // -------------------------------------------------------------------------
    // AC11 — 403 RegistrationRejected
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void registerTenant_403_setsStatusToError() throws Exception {
        when(restTemplate.exchange(
                        anyString(),
                        eq(HttpMethod.POST),
                        any(),
                        any(ParameterizedTypeReference.class)))
                .thenThrow(
                        HttpClientErrorException.create(
                                HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        service.registerTenant();

        assertThat(service.getStatus().isRegistrationError()).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC11 — 410 ALGORITHM_DEPRECATED → publisher stops
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_whenStatus410_publisherStops() throws Exception {
        // Pre-condition: tenant + tournament registered
        String tournamentId = "tourn-1";
        InfoPortalStateRecord stateRec =
                new InfoPortalStateRecord(
                        "venue-1", tournamentId, 5L, "tok", new byte[] {1}, null, "REGISTERED");
        when(stateDao.findByTournament("venue-1", tournamentId)).thenReturn(Optional.of(stateRec));
        when(stateDao.incrementAndGetSeq("venue-1", tournamentId)).thenReturn(6L);

        when(restTemplate.postForEntity(anyString(), any(), eq(Object.class)))
                .thenThrow(
                        HttpClientErrorException.create(HttpStatus.GONE, "Gone", null, null, null));

        service.publishDelta(tournamentId, "{}");

        assertThat(service.getStatus().isAlgorithmDeprecatedHardStop()).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC11 — 409 FULL_RESYNC → snapshot post (NOT re-register)
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_when409_triggersSnapshotPost() throws Exception {
        String tournamentId = "tourn-2";
        InfoPortalStateRecord stateRec =
                new InfoPortalStateRecord(
                        "venue-1", tournamentId, 3L, "tok", new byte[] {1}, null, "REGISTERED");
        when(stateDao.findByTournament("venue-1", tournamentId)).thenReturn(Optional.of(stateRec));
        when(stateDao.incrementAndGetSeq("venue-1", tournamentId)).thenReturn(4L);
        when(stateDao.findCurrentSeq("venue-1", tournamentId)).thenReturn(3L);

        // First call (delta): 409; second call (snapshot): success
        when(restTemplate.postForEntity(anyString(), any(), eq(Object.class)))
                .thenThrow(
                        HttpClientErrorException.create(
                                HttpStatus.CONFLICT, "Conflict", null, null, null))
                .thenReturn(ResponseEntity.ok(null));

        service.publishDelta(tournamentId, "{}");

        // Snapshot post was made (2 total calls: delta + snapshot)
        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(Object.class));
    }

    // -------------------------------------------------------------------------
    // AC11 — 401 SignatureInvalid
    // -------------------------------------------------------------------------

    @Test
    void publishDelta_when401_setsSignatureMismatchStatus() throws Exception {
        String tournamentId = "tourn-3";
        InfoPortalStateRecord stateRec =
                new InfoPortalStateRecord(
                        "venue-1", tournamentId, 1L, "tok", new byte[] {1}, null, "REGISTERED");
        when(stateDao.findByTournament("venue-1", tournamentId)).thenReturn(Optional.of(stateRec));
        when(stateDao.incrementAndGetSeq("venue-1", tournamentId)).thenReturn(2L);

        when(restTemplate.postForEntity(anyString(), any(), eq(Object.class)))
                .thenThrow(
                        HttpClientErrorException.create(
                                HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        service.publishDelta(tournamentId, "{}");

        assertThat(service.getStatus().isSignatureMismatch()).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC10 — deprecation-warning threshold
    // -------------------------------------------------------------------------

    @Test
    void handleAlgorithmWarning_withinThreshold_setsHighSeverity() {
        // 30-day threshold; 20 days remaining → HIGH severity
        AlgorithmWarning warning =
                new AlgorithmWarning("ed25519", LocalDate.now().plusDays(20), 20);
        properties.setDeprecationWarningThresholdDays(30);

        service.handleAlgorithmWarning(warning);

        assertThat(service.getStatus().getDeprecationSeverity())
                .isEqualTo(PublisherStatus.DeprecationSeverity.HIGH);
    }

    @Test
    void handleAlgorithmWarning_beyondThreshold_setsLowSeverity() {
        // 30-day threshold; 90 days remaining → LOW severity (but still warned per DEC-43 D3)
        AlgorithmWarning warning =
                new AlgorithmWarning("ed25519", LocalDate.now().plusDays(90), 90);
        properties.setDeprecationWarningThresholdDays(30);

        service.handleAlgorithmWarning(warning);

        assertThat(service.getStatus().getDeprecationSeverity())
                .isEqualTo(PublisherStatus.DeprecationSeverity.LOW);
        // Warning must still be shown (DEC-43 D3 mandates it regardless of threshold)
        assertThat(service.getStatus().hasDeprecationWarning()).isTrue();
    }

    @Test
    void handleAlgorithmWarning_null_noWarning() {
        service.handleAlgorithmWarning(null);
        assertThat(service.getStatus().hasDeprecationWarning()).isFalse();
    }
}
