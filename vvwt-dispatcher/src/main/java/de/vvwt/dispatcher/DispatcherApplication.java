package de.vvwt.dispatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the vvwt-dispatcher service.
 *
 * <p>{@code @EnableScheduling} is required for the packet timeout sweeper
 * ({@link de.vvwt.dispatcher.packet.PacketTimeoutSweeper}) introduced by E01S07.
 */
@SpringBootApplication
@EnableScheduling
public class DispatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }
}
