// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.ratelimit.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for rate-limiting in vvwt-info-server (E38S07 AC5).
 *
 * <p>All RPM values are expressed as requests-per-minute uniformly (AC2/AC5 unit fix).
 *
 * <p>Bound to the prefix {@code vvwt.info.rate-limit}. Profile-specific defaults are provided in
 * {@code application-self-host.yml} and {@code application-primary.yml}. Misconfiguration with
 * values ≤ 0 causes a {@code ConfigurationPropertiesValidationException} at startup (AC12 —
 * fail-fast).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC5,
 *     AC12</a>
 */
@Validated
@ConfigurationProperties(prefix = "vvwt.info.rate-limit")
public class RateLimitProperties {

    @Valid @NotNull private PerIp perIp = new PerIp();

    @Valid @NotNull private PerTournamentToken perTournamentToken = new PerTournamentToken();

    @NotNull private List<String> trustedProxies = List.of();

    public PerIp getPerIp() {
        return perIp;
    }

    public void setPerIp(PerIp perIp) {
        this.perIp = perIp;
    }

    public PerTournamentToken getPerTournamentToken() {
        return perTournamentToken;
    }

    public void setPerTournamentToken(PerTournamentToken perTournamentToken) {
        this.perTournamentToken = perTournamentToken;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    /** Per-IP token-bucket rate limits (RPM = requests per minute, uniform across AC2/AC5). */
    public static class PerIp {

        /**
         * Maximum requests per minute from a single source IP to publisher endpoints. Default: 600
         * (self-host, ~10 RPS) / 300 (primary, ~5 RPS).
         */
        @Min(1)
        private int publisherRpm;

        /**
         * Maximum requests per minute from a single source IP to HTTP poll endpoints. Default: 1200
         * (self-host, ~20 RPS) / 600 (primary, ~10 RPS).
         */
        @Min(1)
        private int readerPollRpm;

        /**
         * Maximum WS connection attempts per minute from a single source IP. Default: 300
         * (self-host, ~5 RPS) / 120 (primary, ~2 RPS).
         */
        @Min(1)
        private int readerWsRpm;

        public int getPublisherRpm() {
            return publisherRpm;
        }

        public void setPublisherRpm(int publisherRpm) {
            this.publisherRpm = publisherRpm;
        }

        public int getReaderPollRpm() {
            return readerPollRpm;
        }

        public void setReaderPollRpm(int readerPollRpm) {
            this.readerPollRpm = readerPollRpm;
        }

        public int getReaderWsRpm() {
            return readerWsRpm;
        }

        public void setReaderWsRpm(int readerWsRpm) {
            this.readerWsRpm = readerWsRpm;
        }
    }

    /** Per-tournament-token concurrency limits. */
    public static class PerTournamentToken {

        /**
         * Maximum concurrent WebSocket connections for a single tournament token. Default: 1000
         * (same for self-host and primary — bounds tournament-resource-exhaustion regardless of
         * profile).
         */
        @Min(1)
        private int maxConcurrentWs;

        public int getMaxConcurrentWs() {
            return maxConcurrentWs;
        }

        public void setMaxConcurrentWs(int maxConcurrentWs) {
            this.maxConcurrentWs = maxConcurrentWs;
        }
    }
}
