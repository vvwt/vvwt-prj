package de.vvwt.dispatcher.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON Canonicalization Scheme (JCS) per RFC 8785.
 *
 * <p>Produces a canonical UTF-8 byte representation of a JSON value:
 *
 * <ul>
 *   <li>Object keys are sorted lexicographically (Unicode code point order, which equals UTF-16
 *       code unit order for BMP characters — the common case).
 *   <li>Nested objects and arrays are recursively processed.
 *   <li>No insignificant whitespace (compact output).
 *   <li>Numbers, strings, booleans, and null values are serialized by Jackson using its standard
 *       compact JSON rules.
 * </ul>
 *
 * <p>This implementation covers the common subset of RFC 8785 required by E01S06 AC6: canonical
 * UTF-8 bytes of a JSON object representing a {@code phaseDef} payload. It does NOT implement the
 * full RFC 8785 number serialization (IEEE 754 ES6 notation) because the {@code phaseDef} payload
 * contains only integers, which Jackson serializes identically to ES6 notation.
 *
 * <p>This class is a pure stateless utility; all methods are static. Thread-safe.
 */
public final class JcsCanonicalizer {

    /** Shared, thread-safe ObjectMapper configured for compact output. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);

    private JcsCanonicalizer() {
        throw new UnsupportedOperationException("utility class");
    }

    /**
     * Canonicalizes a JSON string according to RFC 8785 JCS.
     *
     * @param jsonText the JSON text to canonicalize; must not be null
     * @return the JCS-canonical UTF-8 byte representation
     * @throws IllegalArgumentException if {@code jsonText} is null
     * @throws IOException if {@code jsonText} is not valid JSON
     */
    public static byte[] canonicalize(String jsonText) throws IOException {
        if (jsonText == null) {
            throw new IllegalArgumentException("jsonText must not be null");
        }
        JsonNode root = MAPPER.readTree(jsonText);
        JsonNode canonical = sortRecursively(root);
        return MAPPER.writeValueAsBytes(canonical);
    }

    /**
     * Canonicalizes a JSON string and returns a {@code String} for diagnostic use. Prefer {@link
     * #canonicalize(String)} for signature operations (returns bytes directly).
     *
     * @param jsonText the JSON text to canonicalize; must not be null
     * @return canonical JSON string (compact, keys sorted, no whitespace)
     * @throws IOException if {@code jsonText} is not valid JSON
     */
    public static String canonicalizeToString(String jsonText) throws IOException {
        return new String(canonicalize(jsonText), StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Recursively sorts all JSON object keys and returns the canonical {@link JsonNode}. Arrays
     * retain element order (per RFC 8785 §3.2.3). Non-object, non-array nodes are returned as-is
     * (leaf values are already canonical).
     */
    private static JsonNode sortRecursively(JsonNode node) {
        if (node.isObject()) {
            // Sort keys: iterate field names, sort by Unicode code point order
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sorted.put(entry.getKey(), sortRecursively(entry.getValue()));
            }
            ObjectNode sortedObject = MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                sortedObject.set(entry.getKey(), entry.getValue());
            }
            return sortedObject;
        } else if (node.isArray()) {
            // Recurse into array elements; preserve order (RFC 8785 §3.2.3)
            ArrayNode sortedArray = MAPPER.createArrayNode();
            List<JsonNode> elements = new ArrayList<>();
            node.forEach(elements::add);
            for (JsonNode element : elements) {
                sortedArray.add(sortRecursively(element));
            }
            return sortedArray;
        } else {
            // Leaf: number, string, boolean, null — return as-is
            return node;
        }
    }
}
