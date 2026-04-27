package de.vvwt.slotopt.dispatcher.cache;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring MVC test slice for {@link CacheController}.
 *
 * <p>Tests the GET /api/cache/{structuralFingerprintHex} endpoint:
 *
 * <ul>
 *   <li>200 OK with JSON body when cache hit
 *   <li>404 Not Found when cache miss
 *   <li>400 Bad Request when hex string is malformed (not 64 hex chars)
 * </ul>
 *
 * <p>DEC-36: mocks {@link ResultsCacheService} via the public interface (not the implementation).
 *
 * <p>TDD RED-first per DEC-22 / AC-CACHE-CONTROLLER (E37S10).
 *
 * <p>Story: E37S10; AC-CACHE-CONTROLLER; DEC-22, DEC-35, DEC-36
 */
@WebMvcTest(CacheController.class)
class CacheControllerSliceTest {

    @Autowired private MockMvc mockMvc;

    /** DEC-36: mock the public interface, never the implementation. */
    @MockitoBean private ResultsCacheService resultsCacheService;

    private static final String VALID_HEX_64 =
            "aabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccddaabbccdd";

    @Test
    void getCachedResult_hit_returns200WithPayload() throws Exception {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0xAA);
        // Build the hex string for a 0xAA-filled 32-byte fingerprint
        String hex = "aa".repeat(32);
        String payload = "{\"bestRank\":1}";
        Instant cachedAt = Instant.parse("2026-04-26T10:00:00Z");

        CachedResult hit = new CachedResult(fingerprint, "game-mode-1", payload, cachedAt);
        when(resultsCacheService.lookup(fingerprint, "game-mode-1")).thenReturn(Optional.of(hit));

        mockMvc.perform(get("/api/cache/{hex}", hex).queryParam("gameMode", "game-mode-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultPayloadJson").value(payload))
                .andExpect(jsonPath("$.gameMode").value("game-mode-1"));
    }

    @Test
    void getCachedResult_miss_returns404() throws Exception {
        byte[] fingerprint = new byte[32];
        Arrays.fill(fingerprint, (byte) 0xBB);
        String hex = "bb".repeat(32);

        when(resultsCacheService.lookup(fingerprint, "any-mode")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/cache/{hex}", hex).queryParam("gameMode", "any-mode"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCachedResult_malformedHex_returns400() throws Exception {
        mockMvc.perform(get("/api/cache/{hex}", "not-valid-hex").queryParam("gameMode", "x"))
                .andExpect(status().isBadRequest());
    }
}
