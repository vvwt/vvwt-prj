package de.vvwt.slotopt.dispatcher.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} that registers the {@link RawPhaseDefSerializer} and {@link
 * RawPhaseDefDeserializer} into a Jackson 2.x {@link ObjectMapper} bean.
 *
 * <p>Spring Boot 4.x auto-configures a Jackson 3.x ({@code tools.jackson.databind.ObjectMapper}) as
 * the primary web-tier mapper. The dispatcher's job and packet services use the Jackson 2.x API
 * ({@code com.fasterxml.jackson.databind}) for {@link RawPhaseDef} ser/deser and JCS
 * canonicalization (via the {@code java-json-canonicalization} library). This config explicitly
 * exposes a Jackson 2.x {@code ObjectMapper} bean so that constructor-injection in {@link
 * de.vvwt.slotopt.dispatcher.job.internal.DefaultJobService} and its peers resolves correctly under
 * SB 4.x (E42S01 — DEC-10 §Spring Boot 4.x baseline).
 *
 * <p>Story: E37S07; AC-RAW-PHASE-DEF-SERIALIZER; E42S01
 */
@Configuration
public class JobJacksonConfig {

    /**
     * Jackson 2.x {@link ObjectMapper} bean — explicitly provided for SB 4.x compatibility.
     *
     * <p>SB 4.x no longer auto-configures a {@code com.fasterxml.jackson.databind.ObjectMapper}
     * bean; this bean fills the gap for the dispatcher's job/packet/result services that depend on
     * the Jackson 2.x API for {@link RawPhaseDef} ser/deser and JCS canonicalization (E42S01).
     *
     * @return a Jackson 2.x {@code ObjectMapper} with the custom {@link RawPhaseDefSerializer} and
     *     {@link RawPhaseDefDeserializer} registered
     */
    @Bean
    public ObjectMapper jackson2ObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("RawPhaseDefModule");
        module.addSerializer(RawPhaseDef.class, new RawPhaseDefSerializer());
        module.addDeserializer(RawPhaseDef.class, new RawPhaseDefDeserializer());
        mapper.registerModule(module);
        return mapper;
    }
}
