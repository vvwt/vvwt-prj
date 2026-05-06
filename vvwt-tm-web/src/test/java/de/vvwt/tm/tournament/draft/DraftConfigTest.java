package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftConfig VO unit test (AC-TDD-DraftConfig).
 *
 * <p>Tests immutability, JSON (de)serialization round-trip, and null-input handling.
 *
 * <p>Inventory: E21S01 line 238.
 *
 * @see DraftConfig
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DraftConfig: empty() factory returns config with no sections. */
    @Test
    void empty_returnsConfigWithNoSections() {
        DraftConfig config = DraftConfig.empty();

        assertThat(config.getSections()).isEmpty();
    }

    /** AC-TDD-DraftConfig: constructor with null sections treats as empty list. */
    @Test
    void constructor_withNullSections_treatsAsEmpty() {
        DraftConfig config = new DraftConfig(null);

        assertThat(config.getSections()).isEmpty();
    }

    /** AC-TDD-DraftConfig: sections list is immutable (attempt to mutate throws). */
    @Test
    void sections_isImmutable() {
        DraftConfig config = DraftConfig.empty();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.getSections().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** AC-TDD-DraftConfig: JSON round-trip preserves empty sections. */
    @Test
    void jsonRoundTrip_emptySections_preservesStructure() throws Exception {
        DraftConfig original = DraftConfig.empty();
        String json = objectMapper.writeValueAsString(original);
        DraftConfig restored = objectMapper.readValue(json, DraftConfig.class);

        assertThat(restored.getSections()).isEmpty();
    }

    /** AC-TDD-DraftConfig: JSON deserialization from {"sections":[]} succeeds. */
    @Test
    void jsonDeserialization_fromEmptySectionsJson_succeeds() throws Exception {
        String json = "{\"sections\":[]}";
        DraftConfig config = objectMapper.readValue(json, DraftConfig.class);

        assertThat(config.getSections()).isEmpty();
    }

    /** AC-TDD-DraftConfig: constructor with non-null list stores sections defensively. */
    @Test
    void constructor_withSections_storesCopy() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));

        assertThat(config.getSections()).hasSize(1);
        assertThat(config.getSections().get(0).getSectionNumber()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-LAST-PHASE-INVARIANT-RED (E48S01)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-LAST-PHASE-INVARIANT-RED: DraftConfig with N sections where the highest sectionNumber
     * has gameMode=roundrobin throws IllegalArgumentException identifying the offending
     * sectionNumber.
     *
     * <p>RED-first per DEC-22 Iron Law. Was RED before validateLastPhaseSiegerehrung() was added.
     *
     * @see <a href="E48S01">E48S01 — last-phase-siegerehrung invariant</a>
     */
    @Test
    void validateLastPhaseSiegerehrung_withLastPhaseRoundrobin_throwsIdentifyingSection() {
        DraftSection phase1 =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftSection phase2 =
                new DraftSection(2, "team_number", 1, "siegerehrung", 5, 10, 15, 1, List.of());
        DraftSection phase3 =
                new DraftSection(3, "team_number", 1, "roundRobin", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(phase1, phase2, phase3));

        assertThatThrownBy(config::validateLastPhaseSiegerehrung)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("siegerehrung");
    }

    /**
     * AC-TEST-LAST-PHASE-INVARIANT-RED: DraftConfig where the highest sectionNumber has
     * gameMode=siegerehrung passes validation without throwing.
     *
     * @see <a href="E48S01">E48S01 — last-phase-siegerehrung invariant</a>
     */
    @Test
    void validateLastPhaseSiegerehrung_withLastPhaseSiegerehrung_passes() {
        DraftSection phase1 =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftSection phase2 =
                new DraftSection(2, "team_number", 1, "siegerehrung", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(phase1, phase2));

        config.validateLastPhaseSiegerehrung(); // must not throw
    }

    /**
     * AC-TEST-LAST-PHASE-INVARIANT-RED: Empty DraftConfig passes validation (no sections → nothing
     * to enforce).
     *
     * @see <a href="E48S01">E48S01 — last-phase-siegerehrung invariant</a>
     */
    @Test
    void validateLastPhaseSiegerehrung_withEmptySections_passes() {
        DraftConfig config = DraftConfig.empty();

        config.validateLastPhaseSiegerehrung(); // must not throw
    }
}
