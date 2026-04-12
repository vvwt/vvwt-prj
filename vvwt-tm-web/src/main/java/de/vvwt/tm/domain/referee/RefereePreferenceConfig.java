package de.vvwt.tm.domain.referee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Parsed representation of a {@code Match.refereePreferenceConfig} JSON blob.
 *
 * <p>The V1 preference schema is intentionally minimal:
 * <pre>
 * {
 *   "preferred": ["uuid1", "uuid2", ...]
 * }
 * </pre>
 * Teams listed under {@code "preferred"} are tried first (in listed order) when selecting
 * a referee for the match, before falling back to the remaining eligible candidates.
 *
 * <p>Parsing is best-effort: malformed JSON or unrecognised fields produce {@link #EMPTY}
 * with a WARN-level log. The assignment algorithm must never throw because of bad preference
 * configuration — a bad config is treated as "no preference".
 *
 * @see RefereeAssigner
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S10.story.md">Story E03S10</a>
 */
public final class RefereePreferenceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RefereePreferenceConfig.class);

    /** Sentinel value for "no preferences configured". Immutable. */
    public static final RefereePreferenceConfig EMPTY =
            new RefereePreferenceConfig(Collections.emptyList());

    private final List<UUID> preferred;

    /**
     * Constructs a preference config with the given preferred-team list.
     *
     * @param preferred ordered list of preferred team IDs (most-preferred first); never {@code null}
     */
    public RefereePreferenceConfig(List<UUID> preferred) {
        if (preferred == null) {
            throw new NullPointerException("preferred list must not be null");
        }
        this.preferred = Collections.unmodifiableList(new ArrayList<>(preferred));
    }

    /**
     * Returns the ordered list of preferred referee team IDs (most-preferred first).
     * An empty list means no preference was configured.
     *
     * @return unmodifiable list of preferred team IDs; never {@code null}
     */
    public List<UUID> getPreferred() {
        return preferred;
    }

    /**
     * Returns {@code true} if no preferences are configured (the list is empty).
     */
    public boolean isEmpty() {
        return preferred.isEmpty();
    }

    /**
     * Parses a {@code referee_preference_config} JSON string using the provided ObjectMapper.
     *
     * <p>Returns {@link #EMPTY} if:
     * <ul>
     *   <li>{@code json} is {@code null} or blank</li>
     *   <li>The JSON is malformed</li>
     *   <li>The {@code "preferred"} field is missing or not an array</li>
     * </ul>
     *
     * @param json         the JSON string from {@code Match.refereePreferenceConfig}; may be {@code null}
     * @param objectMapper the Jackson ObjectMapper to use for parsing
     * @return parsed config, or {@link #EMPTY} on any parse failure
     */
    public static RefereePreferenceConfig parse(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode preferredNode = root.get("preferred");
            if (preferredNode == null || !preferredNode.isArray()) {
                return EMPTY;
            }
            List<UUID> preferredIds = new ArrayList<>();
            for (JsonNode element : preferredNode) {
                String uuidText = element.asText();
                try {
                    preferredIds.add(UUID.fromString(uuidText));
                } catch (IllegalArgumentException e) {
                    LOG.warn("RefereePreferenceConfig: ignoring non-UUID value '{}' in preferred list",
                            uuidText);
                }
            }
            return new RefereePreferenceConfig(preferredIds);
        } catch (JsonProcessingException e) {
            LOG.warn("RefereePreferenceConfig: failed to parse referee_preference_config JSON — "
                    + "using EMPTY preference. Raw value: '{}'. Error: {}", json, e.getMessage());
            return EMPTY;
        }
    }
}
