package de.vvwt.tm.tournament.internal.referee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsed representation of a {@code Match.refereePreferenceConfig} JSON blob (E21S08
 * reconstruction).
 *
 * <p>Reconstruction-in-place counterpart of {@code
 * de.vvwt.tm.domain.referee.RefereePreferenceConfig} (inventory row 278). Lives at {@code
 * de.vvwt.tm.tournament.internal.referee} per AC-PACKAGE-D8.
 *
 * <p>The V1 preference schema is intentionally minimal:
 *
 * <pre>
 * {
 *   "preferred": ["uuid1", "uuid2", ...]
 * }
 * </pre>
 *
 * Teams listed under {@code "preferred"} are tried first (in listed order) when selecting a
 * referee, before falling back to the remaining eligible candidates.
 *
 * <p>Parsing is best-effort: malformed JSON or unrecognised fields return {@link #EMPTY} with a
 * WARN-level log. The assignment algorithm must never throw because of bad preference
 * configuration.
 *
 * <p>Legacy {@code de.vvwt.tm.domain.referee.RefereePreferenceConfig} remains untouched until
 * E21S13 atomic cutover per DEC-32.
 *
 * @see RefereeAssigner
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (reconstruction-in-place)</a>
 * @see <a href="E21S08">E21S08 — inventory row 278</a>
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
     * @param preferred ordered list of preferred team IDs (most-preferred first); must not be
     *     {@code null}
     * @throws NullPointerException if {@code preferred} is null
     */
    public RefereePreferenceConfig(List<UUID> preferred) {
        if (preferred == null) {
            throw new NullPointerException("preferred list must not be null");
        }
        this.preferred = Collections.unmodifiableList(new ArrayList<>(preferred));
    }

    /**
     * Returns the ordered list of preferred referee team IDs (most-preferred first).
     *
     * @return unmodifiable list; never null
     */
    public List<UUID> getPreferred() {
        return preferred;
    }

    /** Returns {@code true} if no preferences are configured. */
    public boolean isEmpty() {
        return preferred.isEmpty();
    }

    /**
     * Parses a {@code referee_preference_config} JSON string.
     *
     * <p>Returns {@link #EMPTY} if {@code json} is null/blank, if the JSON is malformed, if the
     * {@code "preferred"} field is missing or not an array.
     *
     * @param json the JSON string from {@code Match.refereePreferenceConfig}; may be {@code null}
     * @param objectMapper the Jackson ObjectMapper for parsing
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
                    LOG.warn(
                            "RefereePreferenceConfig: ignoring non-UUID value '{}' in preferred"
                                    + " list",
                            uuidText);
                }
            }
            return new RefereePreferenceConfig(preferredIds);
        } catch (JsonProcessingException e) {
            LOG.warn(
                    "RefereePreferenceConfig: failed to parse referee_preference_config JSON —"
                            + " using EMPTY. Raw: '{}'. Error: {}",
                    json,
                    e.getMessage());
            return EMPTY;
        }
    }
}
