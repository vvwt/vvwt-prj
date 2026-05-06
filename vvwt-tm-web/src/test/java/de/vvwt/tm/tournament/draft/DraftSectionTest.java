package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftSection VO unit test (AC-TDD-DraftSection).
 *
 * <p>Tests immutability, JSON (de)serialization round-trip, constructor-validation rejection of
 * invalid input (null/empty/negative).
 *
 * <p>Inventory: E21S01 line 241.
 *
 * @see DraftSection
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftSectionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static DraftSection validSection() {
        return new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());
    }

    /** AC-TDD-DraftSection: valid section constructs without error. */
    @Test
    void constructor_withValidFields_constructsSuccessfully() {
        DraftSection section = validSection();

        assertThat(section.getSectionNumber()).isEqualTo(1);
        assertThat(section.getSortType()).isEqualTo("team_number");
        assertThat(section.getGroupCount()).isEqualTo(2);
        assertThat(section.getGameMode()).isEqualTo("roundRobin");
        assertThat(section.getLapBreakTimeMinutes()).isEqualTo(5);
        assertThat(section.getSectionBreakTimeMinutes()).isEqualTo(10);
        assertThat(section.getLapTimeMinutes()).isEqualTo(15);
        assertThat(section.getSetQuantity()).isEqualTo(1);
        assertThat(section.getBreaks()).isEmpty();
    }

    /** AC-TDD-DraftSection: null breaks treated as empty list. */
    @Test
    void constructor_withNullBreaks_treatsAsEmpty() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, null);

        assertThat(section.getBreaks()).isEmpty();
    }

    /** AC-TDD-DraftSection: breaks list is immutable. */
    @Test
    void breaks_isImmutable() {
        DraftSection section = validSection();

        assertThatThrownBy(() -> section.getBreaks().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** AC-TDD-DraftSection: validate() throws on sectionNumber < 1. */
    @Test
    void validate_withSectionNumberZero_throwsIllegalArgument() {
        DraftSection section =
                new DraftSection(0, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());

        assertThatThrownBy(section::validate).isInstanceOf(IllegalArgumentException.class);
    }

    /** AC-TDD-DraftSection: validate() throws on null sortType. */
    @Test
    void validate_withNullSortType_throwsIllegalArgument() {
        DraftSection section = new DraftSection(1, null, 2, "roundRobin", 5, 10, 15, 1, List.of());

        assertThatThrownBy(section::validate).isInstanceOf(IllegalArgumentException.class);
    }

    /** AC-TDD-DraftSection: validate() throws on invalid sortType. */
    @Test
    void validate_withInvalidSortType_throwsIllegalArgument() {
        DraftSection section =
                new DraftSection(1, "invalid", 2, "roundRobin", 5, 10, 15, 1, List.of());

        assertThatThrownBy(section::validate).isInstanceOf(IllegalArgumentException.class);
    }

    /** AC-TDD-DraftSection: validate() throws on groupCount < 1. */
    @Test
    void validate_withGroupCountZero_throwsIllegalArgument() {
        DraftSection section =
                new DraftSection(1, "team_number", 0, "roundRobin", 5, 10, 15, 1, List.of());

        assertThatThrownBy(section::validate).isInstanceOf(IllegalArgumentException.class);
    }

    /** AC-TDD-DraftSection: validate() throws on lapTimeMinutes <= 0. */
    @Test
    void validate_withLapTimeZero_throwsIllegalArgument() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 0, 1, List.of());

        assertThatThrownBy(section::validate).isInstanceOf(IllegalArgumentException.class);
    }

    /** AC-TDD-DraftSection: validate() accepts placement_group sortType. */
    @Test
    void validate_withPlacementGroupSortType_passes() {
        DraftSection section =
                new DraftSection(1, "placement_group", 2, "roundRobin", 5, 10, 15, 1, List.of());

        section.validate(); // must not throw
    }

    // -------------------------------------------------------------------------
    // AC-TEST-DRAFT-SECTION-WHITELIST-RED (E48S01)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-DRAFT-SECTION-WHITELIST-RED: validate() throws for gameMode not in whitelist.
     *
     * <p>RED-first per DEC-22 Iron Law. Was RED before production whitelist was added.
     *
     * @see <a href="E48S01">E48S01 — gameMode whitelist</a>
     */
    @Test
    void validate_withInvalidGameMode_throwsWithWhitelistMessage() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "invalid_mode", 5, 10, 15, 1, List.of());

        assertThatThrownBy(section::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be one of: roundRobin, siegerehrung");
    }

    /**
     * AC-TEST-DRAFT-SECTION-WHITELIST-RED: validate() accepts siegerehrung as a valid gameMode.
     *
     * <p>Verifies the whitelist expansion includes siegerehrung.
     *
     * @see <a href="E48S01">E48S01 — gameMode whitelist</a>
     */
    @Test
    void validate_withSiegerehrungGameMode_passes() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "siegerehrung", 5, 10, 15, 1, List.of());

        section.validate(); // must not throw
    }

    /** AC-TDD-DraftSection: JSON round-trip preserves all fields. */
    @Test
    void jsonRoundTrip_allFields_preserved() throws Exception {
        DraftSection original = validSection();
        String json = objectMapper.writeValueAsString(original);
        DraftSection restored = objectMapper.readValue(json, DraftSection.class);

        assertThat(restored.getSectionNumber()).isEqualTo(original.getSectionNumber());
        assertThat(restored.getSortType()).isEqualTo(original.getSortType());
        assertThat(restored.getGroupCount()).isEqualTo(original.getGroupCount());
        assertThat(restored.getLapTimeMinutes()).isEqualTo(original.getLapTimeMinutes());
    }
}
