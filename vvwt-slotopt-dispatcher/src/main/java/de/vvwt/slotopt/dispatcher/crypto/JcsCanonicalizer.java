package de.vvwt.slotopt.dispatcher.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Produces the RFC 8785 (JSON Canonicalization Scheme — JCS) canonical UTF-8 byte representation
 * of a {@link JsonNode}.
 *
 * <p>JCS rules (normative, per RFC 8785):
 *
 * <ul>
 *   <li>Object keys are sorted lexicographically by Unicode code-point value (§3.2.3).
 *   <li>No whitespace outside string values (§3.2).
 *   <li>Array element order is preserved (§3.2.2).
 *   <li>Output encoding is UTF-8 (§3.1).
 *   <li>Number serialization follows ES6 Number-to-String rules (§3.2.2.3); for integers this is
 *       equivalent to the usual decimal representation.
 * </ul>
 *
 * <p>This implementation uses Jackson's {@link JsonNode} as the in-memory representation. Key
 * sorting is performed by extracting field names, sorting them with {@link String#compareTo}, and
 * writing fields in sorted order.
 *
 * <p>Spec: E37S04 AC-JCS-CANONICALIZER; E37S02 spec section (a) §JSON Canonicalization;
 * RFC 8785 — JSON Canonicalization Scheme (IETF, 2021).
 *
 * @SpecSource RFC 8785 https://www.rfc-editor.org/rfc/rfc8785
 */
@Component
public class JcsCanonicalizer {

    /**
     * Produces the RFC 8785 canonical UTF-8 byte representation of {@code node}.
     *
     * @param node the JSON node to canonicalize; must not be {@code null}
     * @return the canonical UTF-8 bytes
     * @throws NullPointerException if {@code node} is {@code null}
     * @throws RuntimeException wrapping any {@link IOException} from the serialization process
     */
    public byte[] canonicalize(JsonNode node) {
        if (node == null) {
            throw new NullPointerException("node must not be null");
        }
        var out = new ByteArrayOutputStream();
        try (var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writeNode(node, writer);
        } catch (IOException e) {
            throw new RuntimeException("JCS canonicalization failed", e);
        }
        return out.toByteArray();
    }

    private void writeNode(JsonNode node, OutputStreamWriter writer) throws IOException {
        switch (node.getNodeType()) {
            case OBJECT -> writeObject((ObjectNode) node, writer);
            case ARRAY -> writeArray(node, writer);
            case STRING -> writeString(node.textValue(), writer);
            case NUMBER -> writer.write(node.numberType() == com.fasterxml.jackson.core.JsonParser.NumberType.BIG_DECIMAL
                    || node.numberType() == com.fasterxml.jackson.core.JsonParser.NumberType.FLOAT
                    || node.numberType() == com.fasterxml.jackson.core.JsonParser.NumberType.DOUBLE
                    ? node.decimalValue().stripTrailingZeros().toPlainString()
                    : node.numberValue().toString());
            case BOOLEAN -> writer.write(node.booleanValue() ? "true" : "false");
            case NULL -> writer.write("null");
            default -> throw new IllegalArgumentException("Unsupported node type: " + node.getNodeType());
        }
    }

    private void writeObject(ObjectNode node, OutputStreamWriter writer) throws IOException {
        writer.write('{');
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.sort(String::compareTo);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writeString(keys.get(i), writer);
            writer.write(':');
            writeNode(node.get(keys.get(i)), writer);
        }
        writer.write('}');
    }

    private void writeArray(JsonNode node, OutputStreamWriter writer) throws IOException {
        writer.write('[');
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writeNode(node.get(i), writer);
        }
        writer.write(']');
    }

    private void writeString(String value, OutputStreamWriter writer) throws IOException {
        writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> writer.write("\\\"");
                case '\\' -> writer.write("\\\\");
                case '\b' -> writer.write("\\b");
                case '\f' -> writer.write("\\f");
                case '\n' -> writer.write("\\n");
                case '\r' -> writer.write("\\r");
                case '\t' -> writer.write("\\t");
                default -> {
                    if (c < 0x20) {
                        writer.write(String.format("\\u%04x", (int) c));
                    } else {
                        writer.write(c);
                    }
                }
            }
        }
        writer.write('"');
    }
}
