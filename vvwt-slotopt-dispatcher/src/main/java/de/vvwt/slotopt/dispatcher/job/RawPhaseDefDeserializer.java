// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Jackson {@link JsonDeserializer} for {@link RawPhaseDef}.
 *
 * <p>Deserializes from the wire format defined in spec section (a). Enforces DEC-9 boundary: if any
 * JSON node in the payload contains a UUID-shaped string value, deserialization throws {@link
 * IOException} with message {@code "DEC-9 violation: TeamAvatar UUIDs not permitted in optimizer
 * payload"}.
 *
 * <p>UUID detection: a string value matching the standard UUID pattern {@code
 * [0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}} is classified as a
 * UUID for DEC-9 purposes.
 *
 * <p>Registered via {@link JobJacksonConfig}.
 *
 * <p>Story: E37S07; AC-RAW-PHASE-DEF-SERIALIZER; AC-DEC9-COMPLIANCE; DEC-9
 */
public class RawPhaseDefDeserializer extends JsonDeserializer<RawPhaseDef> {

    /** Standard UUID string pattern per RFC 4122: {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}. */
    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                            + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public RawPhaseDef deserialize(JsonParser parser, DeserializationContext ctx)
            throws IOException {
        JsonNode root = parser.getCodec().readTree(parser);

        // DEC-9 enforcement: scan the entire tree for UUID-shaped string values
        checkForUuids(root);

        int phaseId = root.get("phaseId").asInt();
        int rowCount = root.get("rowCount").asInt();

        JsonNode rowsNode = root.get("rows");
        List<RawRow> rows = new ArrayList<>(rowCount);

        if (rowsNode != null && rowsNode.isArray()) {
            for (JsonNode rowNode : rowsNode) {
                JsonNode positionsNode = rowNode.get("positions");
                List<PositionTuple> positions = new ArrayList<>();
                if (positionsNode != null && positionsNode.isArray()) {
                    for (JsonNode ptNode : positionsNode) {
                        int group = ptNode.get("group").asInt();
                        int pos = ptNode.get("pos").asInt();
                        positions.add(new PositionTuple(group, pos));
                    }
                }
                rows.add(new RawRow(positions));
            }
        }

        return new RawPhaseDef(phaseId, rowCount, rows);
    }

    /**
     * Recursively scans all JSON nodes for UUID-shaped string values.
     *
     * <p>DEC-9: TeamAvatar UUIDs must not appear in any optimizer service payload. This check
     * enforces the constraint at the deserialization boundary.
     *
     * @param node the root node to scan
     * @throws IOException if any UUID-shaped string is found
     */
    private void checkForUuids(JsonNode node) throws IOException {
        if (node.isTextual()) {
            String text = node.asText();
            if (UUID_PATTERN.matcher(text).matches()) {
                throw new IOException(
                        "DEC-9 violation: TeamAvatar UUIDs not permitted in optimizer payload."
                                + " Found UUID-shaped value: '"
                                + text
                                + "'."
                                + " Per DEC-9, TeamAvatars must be represented exclusively by"
                                + " structural (group, pos) tuples — no UUID, name, or"
                                + " identity-bearing attribute may appear in the RawPhaseDef"
                                + " payload.");
            }
        } else if (node.isObject()) {
            // node.properties() replaces deprecated node.fields() in Jackson 2.21+ (E42S01/DEC-29)
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                checkForUuids(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                checkForUuids(element);
            }
        }
    }
}
