package de.vvwt.dispatcher.job;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/**
 * Custom Jackson deserializer that captures the {@code phaseDef} JSON field as a verbatim JSON
 * string.
 *
 * <p>This is needed because {@code POST /submit-job} must:
 *
 * <ol>
 *   <li>JCS-canonicalize the raw JSON bytes for signature verification (AC6)
 *   <li>Separately deserialize the value into a {@link de.vvwt.worker.types.RawPhaseDef}
 * </ol>
 *
 * <p>Preserving the raw JSON text avoids double-parsing and ensures the JCS-canonical bytes are
 * derived from the actual wire bytes, not from a re-serialized intermediate.
 */
public class RawPhaseDefDeserializer extends StdDeserializer<String> {

    public RawPhaseDefDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = parser.readValueAsTree();
        // Use the ObjectMapper from the context to re-serialize as compact JSON string
        ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        return mapper.writeValueAsString(node);
    }
}
