package de.vvwt.dispatcher.cache;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read-only REST endpoint for the results cache (AC9 of E01S09).
 *
 * <h2>Security (AC9, AC10)</h2>
 *
 * <ul>
 *   <li>Read-public: no authentication required on this endpoint.
 *   <li>Write access is handled by {@link ResultsCacheService} using a local DB connection
 *       credential held only by the dispatcher process. This controller exposes read operations
 *       ONLY.
 * </ul>
 *
 * <h2>Endpoint</h2>
 *
 * {@code GET /cache/{fingerprintHex}?scoreFnVersion=N&canonicalizationVersion=M}
 *
 * <ul>
 *   <li>200 — cache hit with result body
 *   <li>404 — cache miss
 *   <li>400 — malformed fingerprint hex (not exactly 64 hex chars = 32 bytes)
 *   <li>503 — DB connection failure (with {@code Retry-After: 5} header)
 * </ul>
 */
@RestController
@RequestMapping("/cache")
public class CacheController {

    private static final int FINGERPRINT_HEX_LENGTH = 64; // 32 bytes × 2 hex chars/byte

    private final ResultsCacheService cacheService;

    public CacheController(ResultsCacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * Retrieves a cached result by structural fingerprint and version identifiers.
     *
     * @param fingerprintHex 64-char hex string encoding the 32-byte fingerprint
     * @param scoreFnVersion scorer algorithm version
     * @param canonicalizationVersion canonicalization algorithm version
     * @return 200 with result body, 404 on miss, 400 on bad fingerprint
     */
    @GetMapping("/{fingerprintHex}")
    public ResponseEntity<CacheResponseBody> getResult(
            @PathVariable("fingerprintHex") String fingerprintHex,
            @RequestParam("scoreFnVersion") int scoreFnVersion,
            @RequestParam("canonicalizationVersion") int canonicalizationVersion) {

        if (!isValidFingerprintHex(fingerprintHex)) {
            return ResponseEntity.badRequest().build();
        }

        byte[] fingerprint = HexFormat.of().parseHex(fingerprintHex);
        Optional<CachedResult> result =
                cacheService.lookup(fingerprint, scoreFnVersion, canonicalizationVersion);

        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CachedResult hit = result.get();
        CacheResponseBody body =
                new CacheResponseBody(
                        fingerprintHex,
                        hit.scoreFnVersion(),
                        hit.canonicalizationVersion(),
                        hit.bestRank(),
                        hit.bestScore(),
                        hit.n(),
                        hit.computedAt());
        return ResponseEntity.ok(body);
    }

    /**
     * Handles DB connectivity failures: returns 503 with {@code Retry-After: 5} header per AC11.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Void> handleDataAccessException(DataAccessException exception) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "5");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).headers(headers).build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static boolean isValidFingerprintHex(String fingerprintHex) {
        if (fingerprintHex == null) {
            return false;
        }
        if (fingerprintHex.length() != FINGERPRINT_HEX_LENGTH) {
            return false;
        }
        // Verify all characters are valid hex digits
        for (char character : fingerprintHex.toCharArray()) {
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Response body record
    // -------------------------------------------------------------------------

    /**
     * JSON response body for a cache hit (AC9).
     *
     * @param fingerprint 64-char hex fingerprint
     * @param scoreFnVersion scorer algorithm version
     * @param canonicalizationVersion canonicalization algorithm version
     * @param bestRank best permutation rank
     * @param bestScore variety score for {@code bestRank}
     * @param n number of avatars
     * @param computedAt when the result was finalized
     */
    public record CacheResponseBody(
            String fingerprint,
            int scoreFnVersion,
            int canonicalizationVersion,
            long bestRank,
            double bestScore,
            int n,
            Instant computedAt) {}
}
