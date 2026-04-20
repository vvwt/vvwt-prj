package de.vvwt.tm.tournament.internal.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;

/**
 * Draft configuration for tournament phase-planning — an ordered list of sections.
 *
 * <p>Each section defines the parameters for one Phase that will be created when the draft is
 * applied (AC-TDD-DraftConfig). Immutable. Jackson deserializes via the {@link
 * JsonCreator}-annotated constructor.
 *
 * <p>Inventory: E21S01 line 238. Reconstructed under {@code de.vvwt.tm.tournament.internal.draft}
 * per DEC-21 package layout. Legacy {@code de.vvwt.tm.domain.draft.DraftConfig} remains active
 * until E21S13 atomic cutover (DEC-32).
 *
 * @see DraftSection
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public final class DraftConfig {

    /** Ordered list of sections. May be empty. */
    private final List<DraftSection> sections;

    /**
     * Jackson-compatible constructor.
     *
     * @param sections ordered list of draft sections; {@code null} treated as empty
     */
    @JsonCreator
    public DraftConfig(@JsonProperty("sections") List<DraftSection> sections) {
        this.sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * Returns an immutable ordered list of sections.
     *
     * @return sections; never {@code null}; may be empty
     */
    public List<DraftSection> getSections() {
        return sections;
    }

    /**
     * Factory: empty draft (no sections configured yet).
     *
     * @return a {@code DraftConfig} with no sections
     */
    public static DraftConfig empty() {
        return new DraftConfig(Collections.emptyList());
    }
}
