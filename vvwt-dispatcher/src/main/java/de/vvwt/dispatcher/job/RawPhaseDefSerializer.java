package de.vvwt.dispatcher.job;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;

/**
 * Custom Jackson serializer that emits the pre-captured {@code phaseDef} JSON string as a raw JSON
 * value (not as a JSON string literal).
 */
public class RawPhaseDefSerializer extends StdSerializer<String> {

    public RawPhaseDefSerializer() {
        super(String.class);
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeRawValue(value);
    }
}
