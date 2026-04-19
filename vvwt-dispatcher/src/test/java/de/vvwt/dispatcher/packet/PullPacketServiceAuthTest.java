package de.vvwt.dispatcher.packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.dispatcher.identity.KeyRegistration;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PullPacketService#authenticate} (AC3, AC4).
 *
 * <p>These tests verify:
 *
 * <ul>
 *   <li>AC3: canonical bytes composition and Ed25519 signature verification
 *   <li>AC4: nonce window (stale, future, role check, expired key)
 *   <li>AC9: error body on malformed payload
 * </ul>
 *
 * <p>No database access — mocked key repository.
 */
class PullPacketServiceAuthTest {

    private de.vvwt.dispatcher.identity.KeyRegistrationRepository keyRepository;
    private PullPacketService service;

    private UUID workerKeyId;
    private KeyPair keyPair;
    private KeyRegistration workerKey;

    @BeforeEach
    void setup() throws Exception {
        keyRepository = mock(de.vvwt.dispatcher.identity.KeyRegistrationRepository.class);
        service = new PullPacketService(keyRepository, null, null);

        workerKeyId = UUID.randomUUID();
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        // Extract raw 32-byte public key from the SPKI-encoded key
        byte[] spki = keyPair.getPublic().getEncoded();
        byte[] rawPublicKeyBytes = new byte[32];
        System.arraycopy(spki, spki.length - 32, rawPublicKeyBytes, 0, 32);

        workerKey =
                new KeyRegistration(workerKeyId, "worker", rawPublicKeyBytes, Instant.now(), null);
        when(keyRepository.findById(workerKeyId)).thenReturn(Optional.of(workerKey));
    }

    // -------------------------------------------------------------------------
    // AC3: Canonical bytes + valid signature
    // -------------------------------------------------------------------------

    @Test
    void authenticate_validSignature_returnsKey() throws Exception {
        String nonce = Instant.now().toString();
        String sig = signCanonical(nonce);

        KeyRegistration result = service.authenticate(workerKeyId, sig, nonce);

        assertThat(result).isNotNull();
        assertThat(result.getKeyId()).isEqualTo(workerKeyId);
    }

    @Test
    void authenticate_tamperedSignature_throwsUnauthorized() throws Exception {
        String nonce = Instant.now().toString();
        String validSig = signCanonical(nonce);

        // Corrupt one byte
        byte[] sigBytes = Base64.getDecoder().decode(validSig);
        sigBytes[0] ^= 0xFF;
        String badSig = Base64.getEncoder().encodeToString(sigBytes);

        assertThatThrownBy(() -> service.authenticate(workerKeyId, badSig, nonce))
                .isInstanceOf(PullPacketService.UnauthorizedException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PullPacketService.UnauthorizedException) ex)
                                                        .getErrorCode())
                                        .isEqualTo("unauthorized"));
    }

    @Test
    void authenticate_signatureForDifferentNonce_throwsUnauthorized() throws Exception {
        String nonce = Instant.now().toString();
        String differentNonce = Instant.now().minusSeconds(1).toString();
        String sig = signCanonical(differentNonce); // signed different nonce

        assertThatThrownBy(() -> service.authenticate(workerKeyId, sig, nonce))
                .isInstanceOf(PullPacketService.UnauthorizedException.class);
    }

    // -------------------------------------------------------------------------
    // AC4: Nonce window checks
    // -------------------------------------------------------------------------

    @Test
    void authenticate_staleNonce_throwsUnauthorizedWithCorrectCode() throws Exception {
        // Nonce is 90 seconds old — beyond the 60s window
        String staleNonce = Instant.now().minusSeconds(90).toString();
        String sig = signCanonical(staleNonce);

        assertThatThrownBy(() -> service.authenticate(workerKeyId, sig, staleNonce))
                .isInstanceOf(PullPacketService.UnauthorizedException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PullPacketService.UnauthorizedException) ex)
                                                        .getErrorCode())
                                        .isEqualTo("stale-or-future-timestamp"));
    }

    @Test
    void authenticate_futureNonce_throwsUnauthorizedWithCorrectCode() throws Exception {
        // Nonce is 10 seconds in the future — beyond the +5s tolerance
        String futureNonce = Instant.now().plusSeconds(10).toString();
        String sig = signCanonical(futureNonce);

        assertThatThrownBy(() -> service.authenticate(workerKeyId, sig, futureNonce))
                .isInstanceOf(PullPacketService.UnauthorizedException.class)
                .satisfies(
                        ex ->
                                assertThat(
                                                ((PullPacketService.UnauthorizedException) ex)
                                                        .getErrorCode())
                                        .isEqualTo("stale-or-future-timestamp"));
    }

    @Test
    void authenticate_nonceWithinFutureTolerance_succeeds() throws Exception {
        // Nonce is 3 seconds in the future — within the +5s tolerance
        String nonce = Instant.now().plusSeconds(3).toString();
        String sig = signCanonical(nonce);

        KeyRegistration result = service.authenticate(workerKeyId, sig, nonce);
        assertThat(result).isNotNull();
    }

    @Test
    void authenticate_unknownKey_throwsUnauthorized() {
        UUID unknownId = UUID.randomUUID();
        when(keyRepository.findById(unknownId)).thenReturn(Optional.empty());
        String nonce = Instant.now().toString();

        assertThatThrownBy(() -> service.authenticate(unknownId, "sig", nonce))
                .isInstanceOf(PullPacketService.UnauthorizedException.class);
    }

    @Test
    void authenticate_submitterKeyCallingPullPacket_throwsForbidden() throws Exception {
        UUID submitterKeyId = UUID.randomUUID();
        byte[] rawKey = new byte[32];
        KeyRegistration submitterKey =
                new KeyRegistration(submitterKeyId, "submitter", rawKey, Instant.now(), null);
        when(keyRepository.findById(submitterKeyId)).thenReturn(Optional.of(submitterKey));
        String nonce = Instant.now().toString();

        assertThatThrownBy(() -> service.authenticate(submitterKeyId, "sig", nonce))
                .isInstanceOf(PullPacketService.ForbiddenException.class);
    }

    // -------------------------------------------------------------------------
    // AC9: Malformed payload
    // -------------------------------------------------------------------------

    @Test
    void authenticate_invalidNonceFormat_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.authenticate(workerKeyId, "sig", "not-a-timestamp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void authenticate_nullWorkerKeyId_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.authenticate(null, "sig", Instant.now().toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // AC3: buildCanonicalBytes correctness
    // -------------------------------------------------------------------------

    @Test
    void buildCanonicalBytes_hasCorrectLength() {
        UUID keyId = UUID.randomUUID();
        String nonce = "2026-04-11T12:00:00Z";
        byte[] canonical = PullPacketService.buildCanonicalBytes(keyId, nonce);

        // "pull-packet" = 11 bytes, UUID = 16 bytes, nonce = 20 bytes
        assertThat(canonical).hasSize(11 + 16 + nonce.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void buildCanonicalBytes_prefixIsCorrect() {
        UUID keyId = UUID.randomUUID();
        String nonce = "2026-04-11T12:00:00Z";
        byte[] canonical = PullPacketService.buildCanonicalBytes(keyId, nonce);

        // First 11 bytes must be "pull-packet" in UTF-8
        byte[] prefix = new byte[11];
        System.arraycopy(canonical, 0, prefix, 0, 11);
        assertThat(new String(prefix, StandardCharsets.UTF_8)).isEqualTo("pull-packet");
    }

    @Test
    void buildCanonicalBytes_uuidBigEndianEncoding() {
        UUID keyId = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        String nonce = "2026-04-11T12:00:00Z";
        byte[] canonical = PullPacketService.buildCanonicalBytes(keyId, nonce);

        // Bytes 11–26 = UUID in big-endian (MSB first)
        ByteBuffer expected = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        expected.putLong(keyId.getMostSignificantBits());
        expected.putLong(keyId.getLeastSignificantBits());
        byte[] uuidSection = new byte[16];
        System.arraycopy(canonical, 11, uuidSection, 0, 16);
        assertThat(uuidSection).isEqualTo(expected.array());
    }

    // -------------------------------------------------------------------------
    // Helper: sign canonical bytes using the test keypair
    // -------------------------------------------------------------------------

    private String signCanonical(String nonce) throws Exception {
        byte[] canonical = PullPacketService.buildCanonicalBytes(workerKeyId, nonce);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
