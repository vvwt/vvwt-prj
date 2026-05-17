// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the {@code vvwt-slotopt-dispatcher} Spring Boot application.
 *
 * <p>The dispatcher exposes the DEC-6 keypair registration endpoint and the slot-optimization
 * packet-dispatch endpoints (per E37S04 and Epic E37). Algorithm-agility is provided by the {@code
 * crypto} package SPI (DEC-43 / E37S04).
 *
 * <p>{@code @EnableScheduling} activates the {@link
 * de.vvwt.slotopt.dispatcher.packet.internal.DefaultPacketTimeoutSweeper} {@code @Scheduled} bean
 * (per AC-PACKET-TIMEOUT-SWEEPER, E37S08).
 *
 * <p>See DEC-10 (Spring Boot module structure), DEC-11 (service boundary), DEC-43
 * (algorithm-agility).
 *
 * <p>E40S03 amendment: {@code @Bean Clock systemUtcClock()} added per AC-CLOCK-INJECTION. {@link
 * de.vvwt.slotopt.dispatcher.identity.internal.DefaultKeyRegistrationService} uses the injected
 * {@link Clock} for deprecation-date enforcement (DEC-43 D2/D3 + DEC-48), making the check testable
 * via {@code Clock.fixed(...)}.
 */
@SpringBootApplication
@EnableScheduling
public class DispatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }

    /**
     * Provides the system UTC clock as a Spring bean for dependency injection.
     *
     * <p>Consumed by {@link
     * de.vvwt.slotopt.dispatcher.identity.internal.DefaultKeyRegistrationService} to evaluate
     * algorithm deprecation-date boundaries per DEC-43 D2/D3 + DEC-48. Tests inject {@code
     * Clock.fixed(...)} via the service constructor to deterministically assert deadline-crossing
     * behavior (AC-CLOCK-INJECTION, E40S03).
     *
     * @return the system UTC clock; never {@code null}
     */
    @Bean
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
