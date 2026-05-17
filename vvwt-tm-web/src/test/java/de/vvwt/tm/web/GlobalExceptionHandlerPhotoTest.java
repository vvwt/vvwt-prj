// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.photo.PhotoFormatException;
import de.vvwt.tm.photo.PhotoSizeException;
import de.vvwt.tm.photo.PhotoStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RED-first tests for Phase 3 photo-exception handler methods in {@link GlobalExceptionHandler}
 * (E36S08, AC-PHASE3-RED-FIRST-PER-METHOD, AC-DEC41-FRESH-RED-FIRST-TESTS,
 * AC-C11-BEHAVIORAL-EQUIVALENCE).
 *
 * <p>Authored fresh per DEC-41 §3 clause (1): PhotoExceptionAdvice had no standalone test files
 * (behavior tested indirectly via TeamPhotoControllerIT). These are NEW tests.
 *
 * <p>Uses standalone {@link MockMvcBuilders#standaloneSetup} — same pattern as {@link
 * GlobalExceptionHandlerTest}. Wires exactly {@link GlobalExceptionHandler} + {@link
 * TestPhotoThrowingController}.
 *
 * <h2>HTTP mapping (AC-C11-BEHAVIORAL-EQUIVALENCE — from deleted PhotoExceptionAdvice)</h2>
 *
 * <ul>
 *   <li>{@link PhotoFormatException} → 400 Bad Request, messageKey {@code error.photo.format}
 *   <li>{@link PhotoSizeException} → 400 Bad Request, messageKey {@code error.photo.tooLarge}
 *   <li>{@link PhotoStorageException} → 500 Internal Server Error, messageKey {@code
 *       error.photo.storage}
 * </ul>
 *
 * @see GlobalExceptionHandler
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-41">DEC-41 — RED-first hierarchy clause 1</a>
 * @see <a href="E36S08">E36S08 — Phase 3 photo exception absorption</a>
 */
@DisplayName("GlobalExceptionHandler — E36S08 Phase 3 photo exception mapping")
class GlobalExceptionHandlerPhotoTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(new TestPhotoThrowingController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    // =========================================================================
    // Phase 3a: PhotoFormatException → 400 (AC-PHASE3-RED-FIRST-PER-METHOD)
    // =========================================================================

    @Test
    @DisplayName("PhotoFormatException maps to HTTP 400 with messageKey error.photo.format")
    void photoFormat_mapsTo400() throws Exception {
        mockMvc.perform(get("/test-photo/format").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messageKey").value("error.photo.format"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // =========================================================================
    // Phase 3b: PhotoSizeException → 400 (AC-PHASE3-RED-FIRST-PER-METHOD)
    // =========================================================================

    @Test
    @DisplayName("PhotoSizeException maps to HTTP 400 with messageKey error.photo.tooLarge")
    void photoSize_mapsTo400() throws Exception {
        mockMvc.perform(get("/test-photo/size").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.messageKey").value("error.photo.tooLarge"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // =========================================================================
    // Phase 3c: PhotoStorageException → 500 (AC-PHASE3-RED-FIRST-PER-METHOD)
    // =========================================================================

    @Test
    @DisplayName("PhotoStorageException maps to HTTP 500 with messageKey error.photo.storage")
    void photoStorage_mapsTo500() throws Exception {
        mockMvc.perform(get("/test-photo/storage").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.messageKey").value("error.photo.storage"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // =========================================================================
    // Minimal test-only controller
    // =========================================================================

    @RestController
    @RequestMapping("/test-photo")
    static class TestPhotoThrowingController {

        @GetMapping("/format")
        void throwPhotoFormat() {
            throw new PhotoFormatException("unsupported image format");
        }

        @GetMapping("/size")
        void throwPhotoSize() {
            throw new PhotoSizeException("photo exceeds maximum size");
        }

        @GetMapping("/storage")
        void throwPhotoStorage() {
            throw new PhotoStorageException("photo storage failure");
        }
    }
}
