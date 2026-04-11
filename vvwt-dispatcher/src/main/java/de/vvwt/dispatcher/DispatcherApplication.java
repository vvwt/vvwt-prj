package de.vvwt.dispatcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the vvwt-dispatcher service.
 *
 * <p>This placeholder enables Spring Boot test slices ({@code @WebMvcTest},
 * {@code @DataJpaTest}) to locate the application configuration. The full
 * implementation of dispatcher endpoints is delivered by E01S06–E01S08.
 *
 * <p>The {@code spring-boot-maven-plugin} repackage is skipped until E01S06
 * is delivered (see dispatcher {@code pom.xml}).
 */
@SpringBootApplication
public class DispatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(DispatcherApplication.class, args);
    }
}
