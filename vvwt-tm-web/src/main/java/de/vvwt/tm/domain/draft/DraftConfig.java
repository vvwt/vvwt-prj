package de.vvwt.tm.domain.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * The draft configuration for a tournament — an ordered list of sections.
 *
 * <p>Each section becomes a {@link de.vvwt.tm.domain.Phase} when the draft is applied (AC5).
 * The draft is a transient planning object: it is stored as a JSON text blob in the
 * {@code tournament.draft_json} column and consumed once during apply. After apply,
 * the Phases are the source of truth.
 *
 * <p>Immutable. Jackson deserializes via the {@link JsonCreator}-annotated constructor.
 *
 * @see DraftSection
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06 AC1</a>
 */
public final class DraftConfig {

    /** Ordered list of sections. May be empty (e.g., for a newly initialized tournament). */
    private final List<DraftSection> sections;

    /**
     * Jackson-compatible constructor.
     *
     * @param sections ordered list of draft sections; must not be {@code null}
     */
    @JsonCreator
    public DraftConfig(@JsonProperty("sections") List<DraftSection> sections) {
        this.sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * Returns an immutable ordered list of sections.
     *
     * @return sections list; never {@code null}; may be empty
     */
    public List<DraftSection> getSections() {
        return sections;
    }

    /**
     * Factory method for an empty draft (no sections configured yet — AC3).
     *
     * @return a {@code DraftConfig} with no sections
     */
    public static DraftConfig empty() {
        return new DraftConfig(Collections.emptyList());
    }
}
