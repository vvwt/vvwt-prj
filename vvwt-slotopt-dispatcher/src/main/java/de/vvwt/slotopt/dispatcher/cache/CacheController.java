package de.vvwt.slotopt.dispatcher.cache;

import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the cache-lookup endpoint.
 *
 * <p>Handles {@code GET /api/cache/{structuralFingerprintHex}}. Returns the cached result for the
 * given structural fingerprint (hex-encoded) and game mode.
 *
 * <p>HTTP status codes per AC-CACHE-CONTROLLER (E37S10):
 *
 * <ul>
 *   <li>200 OK — cache hit; body is a {@link CacheResultResponse} JSON object
 *   <li>404 Not Found — cache miss
 *   <li>400 Bad Request — malformed hex string (not 64 hex chars = 32 bytes)
 * </ul>
 *
 * <p>DEC-35 / DEC-36: depends on {@link ResultsCacheService} (the public interface); the
 * implementation is injected by Spring and must never be referenced directly here.
 *
 * <p>Story: E37S10; AC-CACHE-CONTROLLER; DEC-35, DEC-36
 */
@RestController
public class CacheController {

    private final ResultsCacheService resultsCacheService;

    public CacheController(ResultsCacheService resultsCacheService) {
        this.resultsCacheService = resultsCacheService;
    }

    /**
     * Looks up a cached result by hex-encoded structural fingerprint and game mode.
     *
     * @param structuralFingerprintHex 64-character lowercase hex string (32 bytes)
     * @param gameMode the game-mode discriminator query parameter
     * @return 200 with payload on hit; 404 on miss; 400 on malformed hex
     */
    @GetMapping("/api/cache/{structuralFingerprintHex}")
    public ResponseEntity<?> getCachedResult(
            @PathVariable("structuralFingerprintHex") String structuralFingerprintHex,
            @RequestParam("gameMode") String gameMode) {

        byte[] fingerprint;
        try {
            fingerprint = HexFormat.of().parseHex(structuralFingerprintHex);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid hex fingerprint: " + e.getMessage()));
        }

        if (fingerprint.length != 32) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Structural fingerprint must be exactly 32 bytes (64 hex"
                                            + " chars); got "
                                            + fingerprint.length
                                            + " bytes"));
        }

        Optional<CachedResult> hit = resultsCacheService.lookup(fingerprint, gameMode);
        if (hit.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        CachedResult result = hit.get();
        return ResponseEntity.ok(
                new CacheResultResponse(
                        result.gameMode(),
                        result.resultPayloadJson(),
                        result.cachedAt().toString()));
    }

    /** Handles unexpected {@link IllegalArgumentException} — maps to 400 Bad Request. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Inner DTO (response body)
    // -------------------------------------------------------------------------

    /**
     * JSON response body for a cache hit.
     *
     * @param gameMode the game-mode discriminator
     * @param resultPayloadJson the cached result payload
     * @param cachedAt ISO-8601 timestamp of when the result was first cached
     */
    public record CacheResultResponse(String gameMode, String resultPayloadJson, String cachedAt) {}
}
