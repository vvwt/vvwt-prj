// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.reader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the reader endpoints (E38S06 AC14).
 *
 * <p>Binding prefix: {@code vvwt.info.reader}.
 *
 * <p>Properties:
 *
 * <ul>
 *   <li>{@code poll-cadence-seconds}: server-recommended HTTP poll cadence (default: 5). Sent in
 *       the first WebSocket frame ({@code StreamHello}) so the E38S08 client knows the
 *       server-recommended fallback cadence.
 * </ul>
 *
 * @see <a href="../../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC14</a>
 */
@Component
@ConfigurationProperties(prefix = "vvwt.info.reader")
public class ReaderProperties {

    /** Server-recommended HTTP poll cadence in seconds (default: 5). */
    private int pollCadenceSeconds = 5;

    public int getPollCadenceSeconds() {
        return pollCadenceSeconds;
    }

    public void setPollCadenceSeconds(int pollCadenceSeconds) {
        this.pollCadenceSeconds = pollCadenceSeconds;
    }
}
