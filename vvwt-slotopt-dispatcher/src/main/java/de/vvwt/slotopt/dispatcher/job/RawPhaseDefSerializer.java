package de.vvwt.slotopt.dispatcher.job;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import java.io.IOException;

/**
 * Jackson {@link JsonSerializer} for {@link RawPhaseDef}.
 *
 * <p>Serializes to the wire format defined in spec section (a):
 *
 * <pre>
 * {
 *   "phaseId": int,
 *   "rowCount": int,
 *   "rows": [
 *     {"positions": [{"group": int, "pos": int}, ...]},
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p>DEC-9: {@link RawPhaseDef} contains NO UUIDs by construction (the record type holds only
 * {@code phaseId}, {@code rowCount}, and {@link RawRow} with {@link PositionTuple} values). This
 * serializer does not need to enforce DEC-9 on serialization — it is a structural guarantee of the
 * worker-lib type model. DEC-9 enforcement is in {@link RawPhaseDefDeserializer} (ingestion
 * boundary).
 *
 * <p>Registered via {@link JobJacksonConfig}.
 *
 * <p>Story: E37S07; AC-RAW-PHASE-DEF-SERIALIZER; DEC-9
 */
public class RawPhaseDefSerializer extends JsonSerializer<RawPhaseDef> {

    @Override
    public void serialize(RawPhaseDef value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeStartObject();
        gen.writeNumberField("phaseId", value.phaseId());
        gen.writeNumberField("rowCount", value.rowCount());

        gen.writeArrayFieldStart("rows");
        for (RawRow row : value.rows()) {
            gen.writeStartObject();
            gen.writeArrayFieldStart("positions");
            for (PositionTuple pt : row.positions()) {
                gen.writeStartObject();
                gen.writeNumberField("group", pt.group());
                gen.writeNumberField("pos", pt.pos());
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }
        gen.writeEndArray();

        gen.writeEndObject();
    }
}
