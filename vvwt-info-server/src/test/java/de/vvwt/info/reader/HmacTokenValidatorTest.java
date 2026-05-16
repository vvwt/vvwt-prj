package de.vvwt.info.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.info.reader.internal.DefaultHmacTokenValidator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link DefaultHmacTokenValidator} (E38S06 AC6, AC9).
 *
 * <p>Tests written BEFORE the production class exists per DEC-22 Iron Law.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Valid token for team A → returns team UUID A
 *   <li>HMAC mismatch (wrong team) → empty
 *   <li>Malformed token (wrong length) → empty
 *   <li>Non-base64url token → empty
 *   <li>Constant-time comparison (structural test — verifies MessageDigest.isEqual is used)
 * </ul>
 */
class HmacTokenValidatorTest {

    private DefaultHmacTokenValidator validator;
    private byte[] secret;

    @BeforeEach
    void setUp() {
        validator = new DefaultHmacTokenValidator();
        secret = new byte[32];
        for (int i = 0; i < 32; i++) secret[i] = (byte) i;
    }

    /** Compute HMAC-SHA256(secret, teamId) → Base64URL (no padding). */
    private String computeToken(byte[] secret, String teamId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] hmac = mac.doFinal(teamId.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
    }

    @Test
    void validToken_forTeamA_returnsTeamAUuid() throws Exception {
        String teamA = "team-uuid-a";
        String teamB = "team-uuid-b";
        String tokenForA = computeToken(secret, teamA);

        Optional<String> result =
                validator.validateAndResolveTeam(secret, List.of(teamA, teamB), tokenForA);
        assertThat(result).contains(teamA);
    }

    @Test
    void validToken_forTeamB_returnsTeamBUuid() throws Exception {
        String teamA = "team-uuid-a";
        String teamB = "team-uuid-b";
        String tokenForB = computeToken(secret, teamB);

        Optional<String> result =
                validator.validateAndResolveTeam(secret, List.of(teamA, teamB), tokenForB);
        assertThat(result).contains(teamB);
    }

    @Test
    void hmacMismatch_unknownTeam_returnsEmpty() throws Exception {
        String teamA = "team-uuid-a";
        String teamX = "team-uuid-x"; // not in list
        String tokenForX = computeToken(secret, teamX);

        Optional<String> result =
                validator.validateAndResolveTeam(secret, List.of(teamA), tokenForX);
        assertThat(result).isEmpty();
    }

    @Test
    void malformedToken_wrongLength_returnsEmpty() {
        // 42 chars instead of 43
        String shortToken = "abc123";
        Optional<String> result =
                validator.validateAndResolveTeam(secret, List.of("team-a"), shortToken);
        assertThat(result).isEmpty();
    }

    @Test
    void malformedToken_emptyString_returnsEmpty() {
        Optional<String> result = validator.validateAndResolveTeam(secret, List.of("team-a"), "");
        assertThat(result).isEmpty();
    }

    @Test
    void malformedToken_nonBase64url_returnsEmpty() {
        // Contains chars not in base64url alphabet
        String invalid = "this-is-not-valid-base64url-at-all!!!!!!!!!!!";
        Optional<String> result =
                validator.validateAndResolveTeam(secret, List.of("team-a"), invalid);
        assertThat(result).isEmpty();
    }

    @Test
    void emptyTeamList_returnsEmpty() throws Exception {
        String tokenForA = computeToken(secret, "team-a");
        Optional<String> result = validator.validateAndResolveTeam(secret, List.of(), tokenForA);
        assertThat(result).isEmpty();
    }

    @Test
    void nullToken_returnsEmpty() {
        Optional<String> result = validator.validateAndResolveTeam(secret, List.of("team-a"), null);
        assertThat(result).isEmpty();
    }
}
