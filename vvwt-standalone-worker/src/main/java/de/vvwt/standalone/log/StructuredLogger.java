package de.vvwt.standalone.log;

import org.slf4j.Logger;

/**
 * Thin wrapper around an SLF4J {@link Logger} that supports both plain-text and JSON-structured log
 * output, controlled by a {@code jsonMode} flag.
 *
 * <p>In JSON mode, each log call produces a single-line JSON object:
 *
 * <pre>{"level":"INFO","event":"...","key1":"value1",...}</pre>
 *
 * <p>In plain mode, the output is a human-readable string delegated to SLF4J.
 *
 * <p>Implements Story E01S05 AC8.
 */
public final class StructuredLogger {

    private final Logger logger;
    private final boolean jsonMode;

    /**
     * Constructs a {@code StructuredLogger}.
     *
     * @param logger the backing SLF4J logger
     * @param jsonMode {@code true} to emit single-line JSON; {@code false} for plain text
     */
    public StructuredLogger(Logger logger, boolean jsonMode) {
        this.logger = logger;
        this.jsonMode = jsonMode;
    }

    /**
     * Logs an INFO-level event.
     *
     * @param event short event tag (e.g., "startup", "packet-pull")
     * @param message human-readable message (used in plain mode)
     * @param kvPairs alternating key/value pairs for JSON fields (must be even count)
     */
    public void info(String event, String message, Object... kvPairs) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        if (jsonMode) {
            logger.info(toJson("INFO", event, kvPairs));
        } else {
            logger.info("[{}] {} {}", event, message, formatKv(kvPairs));
        }
    }

    /**
     * Logs a WARN-level event.
     *
     * @param event short event tag
     * @param message human-readable message
     * @param kvPairs alternating key/value pairs
     */
    public void warn(String event, String message, Object... kvPairs) {
        if (!logger.isWarnEnabled()) {
            return;
        }
        if (jsonMode) {
            logger.warn(toJson("WARN", event, kvPairs));
        } else {
            logger.warn("[{}] {} {}", event, message, formatKv(kvPairs));
        }
    }

    /**
     * Logs an ERROR-level event.
     *
     * @param event short event tag
     * @param message human-readable message
     * @param kvPairs alternating key/value pairs
     */
    public void error(String event, String message, Object... kvPairs) {
        if (!logger.isErrorEnabled()) {
            return;
        }
        if (jsonMode) {
            logger.error(toJson("ERROR", event, kvPairs));
        } else {
            logger.error("[{}] {} {}", event, message, formatKv(kvPairs));
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String toJson(String level, String event, Object[] kvPairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"level\":\"").append(jsonEscape(level)).append("\"");
        sb.append(",\"event\":\"").append(jsonEscape(event)).append("\"");
        if (kvPairs != null) {
            for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                String key = String.valueOf(kvPairs[i]);
                Object val = kvPairs[i + 1];
                sb.append(",\"").append(jsonEscape(key)).append("\":");
                if (val instanceof Number || val instanceof Boolean) {
                    sb.append(val);
                } else {
                    sb.append("\"").append(jsonEscape(String.valueOf(val))).append("\"");
                }
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String formatKv(Object[] kvPairs) {
        if (kvPairs == null || kvPairs.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(kvPairs[i]).append('=').append(kvPairs[i + 1]);
        }
        return sb.toString();
    }

    /** Minimal JSON string escaping — handles control characters and quotes. */
    static String jsonEscape(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
