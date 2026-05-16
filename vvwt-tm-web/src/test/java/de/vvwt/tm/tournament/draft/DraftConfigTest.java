package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftConfig VO unit test (AC-TDD-DraftConfig).
 *
 * <p>Tests immutability, JSON (de)serialization round-trip, null-input handling, last-phase
 * invariant (E48S01), and first-phase invariant (E48S16).
 *
 * <p>Inventory: E21S01 line 238.
 *
 * @see DraftConfig
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E48S01">E48S01 — last-phase-awardCeremony invariant</a>
 * @see <a href="E48S16">E48S16 — first-phase sortType=team_number invariant</a>
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
     * has gameMode=roundRobin throws IllegalArgumentException identifying the offending
     * sectionNumber.
     *
     * <p>RED-first per DEC-22 Iron Law. Was RED before validateLastPhaseAwardCeremony() was added.
     * Renamed from validateLastPhaseSiegerehrung by E58S04 (DEC-73 D-7).
     *
     * @see <a href="E48S01">E48S01 — last-phase-awardCeremony invariant</a>
     */
    @Test
    void validateLastPhaseAwardCeremony_withLastPhaseRoundRobin_throwsIdentifyingSection() {
        DraftSection phase1 =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftSection phase2 =
                new DraftSection(2, "team_number", 1, "awardCeremony", 5, 10, 15, 1, List.of());
        DraftSection phase3 =
                new DraftSection(3, "team_number", 1, "roundRobin", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(phase1, phase2, phase3));

        assertThatThrownBy(config::validateLastPhaseAwardCeremony)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("awardCeremony");
    }

    /**
     * AC-TEST-LAST-PHASE-INVARIANT-RED: DraftConfig where the highest sectionNumber has
     * gameMode=awardCeremony passes validation without throwing.
     *
     * <p>Renamed from validateLastPhaseSiegerehrung by E58S04 (DEC-73 D-7).
     *
     * @see <a href="E48S01">E48S01 — last-phase-awardCeremony invariant</a>
     */
    @Test
    void validateLastPhaseAwardCeremony_withLastPhaseAwardCeremony_passes() {
        DraftSection phase1 =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftSection phase2 =
                new DraftSection(2, "team_number", 1, "awardCeremony", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(phase1, phase2));

        config.validateLastPhaseAwardCeremony(); // must not throw
    }

    /**
     * AC-TEST-LAST-PHASE-INVARIANT-RED: Empty DraftConfig passes validation (no sections → nothing
     * to enforce).
     *
     * <p>Renamed from validateLastPhaseSiegerehrung by E58S04 (DEC-73 D-7).
     *
     * @see <a href="E48S01">E48S01 — last-phase-awardCeremony invariant</a>
     */
    @Test
    void validateLastPhaseAwardCeremony_withEmptySections_passes() {
        DraftConfig config = DraftConfig.empty();

        config.validateLastPhaseAwardCeremony(); // must not throw
    }

    // -------------------------------------------------------------------------
    // AC-TEST-FIRST-PHASE-INVARIANT-RED (E48S16)
    // RED-first per DEC-22 Iron Law. Tests written BEFORE validateFirstPhaseTeamNumber() exists.
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-FIRST-PHASE-INVARIANT-RED: DraftConfig where the first section (lowest sectionNumber)
     * has sortType=placement_group throws IllegalArgumentException identifying the offending
     * sectionNumber and actual sortType.
     *
     * <p>RED-first per DEC-22 Iron Law (Q-1a). Was RED before validateFirstPhaseTeamNumber() was
     * added.
     *
     * @see <a href="E48S16">E48S16 — first-phase sortType=team_number invariant</a>
     */
    @Test
    void validateFirstPhaseTeamNumber_withFirstPhasePlacementGroup_throwsIdentifyingSection() {
        DraftSection phase1 =
                new DraftSection(1, "placement_group", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftSection phase2 =
                new DraftSection(2, "team_number", 1, "awardCeremony", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(phase1, phase2));

        assertThatThrownBy(config::validateFirstPhaseTeamNumber)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("team_number");
    }

    /**
     * AC-TEST-FIRST-PHASE-INVARIANT-RED: DraftConfig where the first section (lowest sectionNumber)
     * has sortType=group_placement throws IllegalArgumentException.
     *
     * <p>RED-first per DEC-22 Iron Law (Q-1a).
     *
     * @see <a href="E48S16">E48S16 — first-phase sortType=team_number invariant</a>
     */
    @Test
    void validateFirstPhaseTeamNumber_withFirstPhaseGroupPlacement_throwsIdentifyingSection() {
        DraftSection phase1 =
                new DraftSection(1, "group_placement", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftSection phase2 =
                new DraftSection(2, "team_number", 1, "awardCeremony", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(phase1, phase2));

        assertThatThrownBy(config::validateFirstPhaseTeamNumber)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("team_number");
    }

    // -------------------------------------------------------------------------
    // AC-TEST-FIRST-PHASE-VALID-GREEN (E48S16)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-FIRST-PHASE-VALID-GREEN: DraftConfig with first section sortType=team_number is
     * accepted even when further sections have other sortTypes.
     *
     * @see <a href="E48S16">E48S16 — first-phase sortType=team_number invariant</a>
     */
    @Test
    void validateFirstPhaseTeamNumber_withFirstPhaseTeamNumber_passes() {
        DraftSection phase1 =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftSection phase2 =
                new DraftSection(2, "placement_group", 1, "roundRobin", 5, 10, 15, 1, List.of());
        DraftSection phase3 =
                new DraftSection(3, "group_placement", 1, "awardCeremony", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(phase1, phase2, phase3));

        config.validateFirstPhaseTeamNumber(); // must not throw
    }

    /**
     * AC-TEST-FIRST-PHASE-VALID-GREEN: Empty DraftConfig passes first-phase validation (no sections
     * → nothing to enforce). Mirror of validateLastPhaseAwardCeremony empty-section no-op.
     *
     * @see <a href="E48S16">E48S16 — first-phase sortType=team_number invariant</a>
     */
    @Test
    void validateFirstPhaseTeamNumber_withEmptySections_passes() {
        DraftConfig config = DraftConfig.empty();

        config.validateFirstPhaseTeamNumber(); // must not throw
    }
}
