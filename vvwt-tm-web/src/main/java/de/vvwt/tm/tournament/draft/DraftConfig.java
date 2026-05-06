package de.vvwt.tm.tournament.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Draft configuration for tournament phase-planning — an ordered list of sections.
 *
 * <p>Each section defines the parameters for one Phase that will be created when the draft is
 * applied (AC-TDD-DraftConfig). Immutable. Jackson deserializes via the {@link
 * JsonCreator}-annotated constructor.
 *
 * <p>Inventory: E21S01 line 238. Named-interface sub-package placement by E33S04 (DEC-35 retrofit).
 * Legacy {@code de.vvwt.tm.domain.draft.DraftConfig} remains active until E21S13 atomic cutover
 * (DEC-32).
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

    /**
     * Validates that the last phase (highest {@code sectionNumber}) has {@code
     * gameMode=siegerehrung}, as required by the D-10 invariant.
     *
     * <p>No-op if there are no sections (empty draft has nothing to enforce).
     *
     * @throws IllegalArgumentException if the last phase does not have {@code
     *     gameMode=siegerehrung}; the message identifies the offending sectionNumber and its actual
     *     gameMode
     * @see <a href="E48S01">E48S01 — last-phase-siegerehrung invariant (D-10)</a>
     */
    public void validateLastPhaseSiegerehrung() {
        if (sections.isEmpty()) {
            return;
        }
        DraftSection lastSection =
                sections.stream()
                        .max(Comparator.comparingInt(DraftSection::getSectionNumber))
                        .orElseThrow();
        if (!"siegerehrung".equals(lastSection.getGameMode())) {
            throw new IllegalArgumentException(
                    "Last phase (sectionNumber "
                            + lastSection.getSectionNumber()
                            + ") must have gameMode=siegerehrung, got: "
                            + lastSection.getGameMode());
        }
    }
}
