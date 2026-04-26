package de.vvwt.slotopt.dispatcher.job;

import com.fasterxml.jackson.databind.module.SimpleModule;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} that registers the {@link RawPhaseDefSerializer} and {@link
 * RawPhaseDefDeserializer} into Spring Boot's default {@link
 * com.fasterxml.jackson.databind.ObjectMapper}.
 *
 * <p>Spring Boot auto-configures the {@code ObjectMapper} via {@code JacksonAutoConfiguration}. By
 * contributing a {@link com.fasterxml.jackson.databind.Module} bean, this config wires the custom
 * serializers/deserializers into both the web tier and any explicit {@code ObjectMapper} injections
 * within this application.
 *
 * <p>Story: E37S07; AC-RAW-PHASE-DEF-SERIALIZER
 */
@Configuration
public class JobJacksonConfig {

    /**
     * Registers custom RawPhaseDef ser/deser as a Jackson {@link
     * com.fasterxml.jackson.databind.Module}.
     *
     * <p>Spring Boot's {@code JacksonAutoConfiguration} picks up all {@code Module} beans
     * automatically.
     *
     * @return a module with custom {@link RawPhaseDefSerializer} and {@link
     *     RawPhaseDefDeserializer}
     */
    @Bean
    public SimpleModule rawPhaseDefJacksonModule() {
        SimpleModule module = new SimpleModule("RawPhaseDefModule");
        module.addSerializer(RawPhaseDef.class, new RawPhaseDefSerializer());
        module.addDeserializer(RawPhaseDef.class, new RawPhaseDefDeserializer());
        return module;
    }
}
