// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.ratelimit.config;

import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.ratelimit.IpRateLimiter;
import de.vvwt.info.ratelimit.RateLimitAuditService;
import de.vvwt.info.ratelimit.SourceIpExtractor;
import de.vvwt.info.ratelimit.TournamentConcurrencyLimiter;
import de.vvwt.info.ratelimit.internal.DefaultIpRateLimiter;
import de.vvwt.info.ratelimit.internal.DefaultRateLimitAuditService;
import de.vvwt.info.ratelimit.internal.DefaultSourceIpExtractor;
import de.vvwt.info.ratelimit.internal.DefaultTournamentConcurrencyLimiter;
import de.vvwt.info.ratelimit.internal.RateLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration wiring the rate-limiting subsystem beans (E38S07 AC5, AC6).
 *
 * <p>Registers {@link RateLimitFilter} as a {@link FilterRegistrationBean} with order {@code
 * Integer.MIN_VALUE} so it executes before all application filters (AC6). The filter is applied to
 * all URL patterns.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S07.story.md">E38S07 AC5,
 *     AC6</a>
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public SourceIpExtractor sourceIpExtractor(RateLimitProperties props) {
        return new DefaultSourceIpExtractor(props.getTrustedProxies());
    }

    @Bean
    public IpRateLimiter ipRateLimiter(RateLimitProperties props) {
        RateLimitProperties.PerIp perIp = props.getPerIp();
        return new DefaultIpRateLimiter(
                perIp.getPublisherRpm(), perIp.getReaderPollRpm(), perIp.getReaderWsRpm());
    }

    @Bean
    public TournamentConcurrencyLimiter tournamentConcurrencyLimiter(RateLimitProperties props) {
        return new DefaultTournamentConcurrencyLimiter(
                props.getPerTournamentToken().getMaxConcurrentWs());
    }

    @Bean
    public RateLimitAuditService rateLimitAuditService(AuditLogDao auditLogDao) {
        return new DefaultRateLimitAuditService(auditLogDao);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            SourceIpExtractor sourceIpExtractor,
            IpRateLimiter ipRateLimiter,
            TournamentConcurrencyLimiter concurrencyLimiter,
            RateLimitAuditService auditService) {

        RateLimitFilter filter =
                new RateLimitFilter(
                        sourceIpExtractor, ipRateLimiter, concurrencyLimiter, auditService);
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE); // first filter in chain (AC6)
        return registration;
    }
}
