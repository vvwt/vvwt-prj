// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubmitResultResponse}.
 *
 * <p>RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S09).
 *
 * <p>Story: E37S09; AC-SUBMIT-RESULT-DTOs; DEC-22
 */
class SubmitResultResponseTest {

    @Test
    void acceptedTrueHasNullReason() {
        SubmitResultResponse resp = new SubmitResultResponse(true, null);
        assertThat(resp.accepted()).isTrue();
        assertThat(resp.reason()).isNull();
    }

    @Test
    void acceptedFalseHasSupersededReason() {
        SubmitResultResponse resp = new SubmitResultResponse(false, "superseded");
        assertThat(resp.accepted()).isFalse();
        assertThat(resp.reason()).isEqualTo("superseded");
    }
}
