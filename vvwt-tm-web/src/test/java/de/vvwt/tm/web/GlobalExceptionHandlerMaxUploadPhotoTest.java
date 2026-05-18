// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * RED-first tests for path-aware {@link MaxUploadSizeExceededException} routing in {@link
 * GlobalExceptionHandler} (E12S08, AC3, AC4).
 *
 * <p>The bug: {@code handleMaxUploadSizeExceeded} was using {@code "error.audio.tooLarge"} as the
 * messageKey for ALL paths, including photo uploads at {@code /api/photo/...}. This produced an
 * audio-domain error message when a large photo was rejected by the Spring multipart layer.
 *
 * <p>Fix: the handler routes to {@code "error.photo.tooLarge"} when the request URI starts with
 * {@code /api/photo/}; otherwise retains {@code "error.audio.tooLarge"} for audio and other paths.
 *
 * <p>Tests are authored RED-first per DEC-22 Iron Law: at commit time, {@code
 * handleMaxUploadSizeExceeded} always uses {@code "error.audio.tooLarge"} regardless of path — both
 * tests fail until the production fix is applied.
 *
 * @see GlobalExceptionHandler#handleMaxUploadSizeExceeded
 * @see de.vvwt.tm.tournament.ApiErrorResponse
 * @since E12S08
 */
@DisplayName("GlobalExceptionHandler — E12S08: path-aware MaxUploadSizeExceeded routing")
class GlobalExceptionHandlerMaxUploadPhotoTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TestMaxUploadController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    // =========================================================================
    // AC3 + AC4: MaxUploadSizeExceededException on /api/photo/ path → error.photo.tooLarge
    // =========================================================================

    @Test
    @DisplayName(
            "AC3/AC4: MaxUploadSizeExceededException on /api/photo/ path returns messageKey"
                    + " error.photo.tooLarge (not error.audio.tooLarge)")
    void maxUploadOnPhotoPath_returnsPhotoTooLargeKey() throws Exception {
        mockMvc.perform(
                        get("/api/photo/tournaments/test-tournament/teams/test-team")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.messageKey").value("error.photo.tooLarge"));
    }

    // =========================================================================
    // AC3 guard: MaxUploadSizeExceededException on /api/audio/ path → error.audio.tooLarge
    // (audio behaviour unchanged)
    // =========================================================================

    @Test
    @DisplayName(
            "Guard: MaxUploadSizeExceededException on /api/audio/ path retains messageKey"
                    + " error.audio.tooLarge")
    void maxUploadOnAudioPath_retainsAudioTooLargeKey() throws Exception {
        mockMvc.perform(get("/api/audio/something").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.messageKey").value("error.audio.tooLarge"));
    }

    // =========================================================================
    // Minimal test-only controller that throws MaxUploadSizeExceededException
    // =========================================================================

    @RestController
    @RequestMapping
    static class TestMaxUploadController {

        @GetMapping("/api/photo/tournaments/{tournamentId}/teams/{teamId}")
        void throwMaxUploadOnPhotoPath() {
            throw new MaxUploadSizeExceededException(26214400L);
        }

        @GetMapping("/api/audio/something")
        void throwMaxUploadOnAudioPath() {
            throw new MaxUploadSizeExceededException(10485760L);
        }
    }
}
