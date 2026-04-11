package de.vvwt.dispatcher.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link CacheController} (AC9, AC11 of E01S09).
 */
@WebMvcTest(CacheController.class)
class CacheControllerTest {

    // Valid 64-char hex string (32 zero bytes)
    private static final String VALID_FINGERPRINT_HEX = "0".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResultsCacheService cacheService;

    // -------------------------------------------------------------------------
    // AC9 — 200 on hit
    // -------------------------------------------------------------------------

    @Test
    void get_cacheHit_returns200WithBody() throws Exception {
        byte[] fingerprint = new byte[32]; // 32 zero bytes
        CachedResult hit = new CachedResult(
                fingerprint, 1, 1, 42L, 3.14, 8, Instant.parse("2026-04-11T12:00:00Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));

        when(cacheService.lookup(any(byte[].class), eq(1), eq(1)))
                .thenReturn(Optional.of(hit));

        mockMvc.perform(get("/cache/" + VALID_FINGERPRINT_HEX)
                        .param("scoreFnVersion", "1")
                        .param("canonicalizationVersion", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestRank").value(42))
                .andExpect(jsonPath("$.bestScore").value(3.14))
                .andExpect(jsonPath("$.n").value(8))
                .andExpect(jsonPath("$.scoreFnVersion").value(1))
                .andExpect(jsonPath("$.canonicalizationVersion").value(1));
    }

    // -------------------------------------------------------------------------
    // AC9 — 404 on miss
    // -------------------------------------------------------------------------

    @Test
    void get_cacheMiss_returns404() throws Exception {
        when(cacheService.lookup(any(byte[].class), eq(1), eq(1)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/cache/" + VALID_FINGERPRINT_HEX)
                        .param("scoreFnVersion", "1")
                        .param("canonicalizationVersion", "1"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // AC11 — 400 on malformed fingerprint (wrong length)
    // -------------------------------------------------------------------------

    @Test
    void get_shortFingerprintHex_returns400() throws Exception {
        mockMvc.perform(get("/cache/deadbeef")
                        .param("scoreFnVersion", "1")
                        .param("canonicalizationVersion", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_fingerprintHexTooLong_returns400() throws Exception {
        mockMvc.perform(get("/cache/" + "a".repeat(65))
                        .param("scoreFnVersion", "1")
                        .param("canonicalizationVersion", "1"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // AC11 — 503 with Retry-After on DB failure
    // -------------------------------------------------------------------------

    @Test
    void get_dbFailure_returns503WithRetryAfter() throws Exception {
        when(cacheService.lookup(any(byte[].class), eq(1), eq(1)))
                .thenThrow(new DataAccessResourceFailureException("H2 down"));

        mockMvc.perform(get("/cache/" + VALID_FINGERPRINT_HEX)
                        .param("scoreFnVersion", "1")
                        .param("canonicalizationVersion", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));
    }
}
