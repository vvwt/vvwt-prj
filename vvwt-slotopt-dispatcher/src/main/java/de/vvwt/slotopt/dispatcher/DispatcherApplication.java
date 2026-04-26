package de.vvwt.slotopt.dispatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the {@code vvwt-slotopt-dispatcher} Spring Boot application.
 *
 * <p>The dispatcher exposes the DEC-6 keypair registration endpoint and the slot-optimization
 * packet-dispatch endpoints (per E37S04 and Epic E37). Algorithm-agility is provided by the {@code
 * crypto} package SPI (DEC-43 / E37S04).
 *
 * <p>{@code @EnableScheduling} activates the {@link
 * de.vvwt.slotopt.dispatcher.packet.PacketTimeoutSweeper} {@code @Scheduled} bean (per
 * AC-PACKET-TIMEOUT-SWEEPER, E37S08).
 *
 * <p>See DEC-10 (Spring Boot module structure), DEC-11 (service boundary), DEC-43
 * (algorithm-agility).
 */
@SpringBootApplication
@EnableScheduling
public class DispatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }
}
