package de.vvwt.info;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Public Participant Info Service — Spring Boot application entry point.
 *
 * <p>This is the minimal {@code @SpringBootApplication} scaffold for E38S01. No business logic, no
 * endpoints, no DAO — those belong to E38S03–E38S09.
 *
 * <p>DEC-42 D3 — Default-profile-is-self-host (drive-by-squatting mitigation): When no {@code
 * spring.profiles.active} is set, the application activates the {@code self-host} profile by
 * default (configured via {@code spring.profiles.default=self-host} in application.yml). This
 * prevents an internet-reachable freshly-installed self-host from accepting opportunistic
 * second-tenant registrations without operator intent.
 *
 * <p>Both profiles ({@code self-host} and {@code primary}) boot cleanly per AC10.
 *
 * <p>Story: E38S01 — DEC-42, DEC-10.
 */
@SpringBootApplication
public class InfoServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfoServerApplication.class, args);
    }
}
