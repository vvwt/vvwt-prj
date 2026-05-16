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
 * <h2>E58S02 — distributionMode Enum→String migration (DEC-73 D-2)</h2>
 *
 * <p>Constructor calls updated from {@code DistributionMode} enum constants to {@code String}
 * literals. Assertions updated from enum equality to String equality.
 *
 * @see DraftSection
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S20">E51S20 — gameMode/distributionMode String→Enum migration</a>
 * @see <a href="E58S02">E58S02 — distributionMode Enum→String migration</a>
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
    // AC-TEST-DRAFT-SECTION-WHITELIST-RED (E48S01) — updated for E51S20
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-DRAFT-SECTION-WHITELIST-RED / E51S20: validate() throws for null gameMode.
     *
     * <p>In E51S20, unknown wire-format values are rejected at Jackson deserialization time via
     * GameMode.fromWireFormat (removed by E58S01) ({@link InvalidFormatException}). The only way to
     * trigger the validate() null-check is to pass null directly via the constructor.
     *
     * @see <a href="E48S01">E48S01 — gameMode whitelist</a>
     * @see <a href="E51S20">E51S20 — String→Enum: invalid value rejected at deserialization</a>
     */
    @Test
    void validate_withNullGameMode_throwsIllegalArgument() {
        DraftSection section = new DraftSection(1, "team_number", 2, null, 5, 10, 15, 1, List.of());

        assertThatThrownBy(section::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gameMode");
    }

    /**
     * AC-TEST-DRAFT-SECTION-WHITELIST-RED: validate() accepts siegerehrung as a valid gameMode.
     *
     * <p>Verifies siegerehrung string key is a valid enum constant.
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

    // -------------------------------------------------------------------------
    // AC-TEST-DRAFTSECTION-DISTRIBUTION-MODE-FIELD-EXISTS-RED (E51S15)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-DRAFTSECTION-DISTRIBUTION-MODE-FIELD-EXISTS-RED: {@link DraftSection} exposes a
     * {@code getDistributionMode()} accessor returning {@code "sequential"} when the field is
     * absent from JSON.
     *
     * <p>RED-first per DEC-22 Iron Law. Updated by E58S02: distributionMode is now a String.
     *
     * @see <a href="E51S15">E51S15 — distribution_mode feature</a>
     * @see <a href="DEC-14">DEC-14 — persistence in draft_json (no Flyway migration)</a>
     * @see <a href="E58S02">E58S02 — migrated from DistributionMode enum to String</a>
     */
    @Test
    void distributionMode_defaultsToSequential_whenNotInJson() throws Exception {
        // JSON without distributionMode field → should default to "sequential"
        String jsonWithoutField =
                "{\"sectionNumber\":1,\"sortType\":\"team_number\",\"groupCount\":2,"
                        + "\"gameMode\":\"roundRobin\",\"lapBreakTimeMinutes\":0,"
                        + "\"sectionBreakTimeMinutes\":0,\"lapTimeMinutes\":15,\"setQuantity\":1,"
                        + "\"breaks\":[]}";
        DraftSection section = objectMapper.readValue(jsonWithoutField, DraftSection.class);

        assertThat(section.getDistributionMode())
                .as(
                        "distributionMode must default to 'sequential' when absent from draft_json"
                                + " (AC-TEST-DRAFTSECTION-DISTRIBUTION-MODE-FIELD-EXISTS-RED,"
                                + " DEC-14 H2 JSON column)")
                .isEqualTo("sequential");
    }

    /**
     * AC-TEST-DRAFTSECTION-DISTRIBUTION-MODE-FIELD-EXISTS-RED: Jackson correctly deserializes
     * {@code distributionMode} from JSON.
     *
     * <p>RED-first per DEC-22 Iron Law. Updated by E58S02: distributionMode is now a String.
     *
     * @see <a href="E58S02">E58S02 — migrated from DistributionMode enum to String</a>
     */
    @Test
    void distributionMode_deserializesFromJson_whenPresent() throws Exception {
        String jsonWithRoundRobin =
                "{\"sectionNumber\":1,\"sortType\":\"team_number\",\"groupCount\":2,"
                        + "\"gameMode\":\"roundRobin\",\"lapBreakTimeMinutes\":0,"
                        + "\"sectionBreakTimeMinutes\":0,\"lapTimeMinutes\":15,\"setQuantity\":1,"
                        + "\"breaks\":[],\"distributionMode\":\"round_robin\"}";
        DraftSection section = objectMapper.readValue(jsonWithRoundRobin, DraftSection.class);

        assertThat(section.getDistributionMode())
                .as("distributionMode must deserialize from JSON as 'round_robin'")
                .isEqualTo("round_robin");
    }

    /**
     * AC-TEST-DRAFTSECTION-DISTRIBUTION-MODE-FIELD-EXISTS-RED: JSON round-trip preserves {@code
     * distributionMode}.
     *
     * <p>RED-first per DEC-22 Iron Law.
     */
    @Test
    void distributionMode_roundTripPreservesValue() throws Exception {
        DraftSection original = validSection(); // defaults to SEQUENTIAL
        String json = objectMapper.writeValueAsString(original);
        DraftSection restored = objectMapper.readValue(json, DraftSection.class);

        assertThat(restored.getDistributionMode())
                .as("distributionMode must survive JSON round-trip")
                .isEqualTo(original.getDistributionMode());
    }

    // -------------------------------------------------------------------------
    // AC-ERROR-HANDLING-INVALID-DISTRIBUTION-MODE (E51S15 / E58S02)
    // -------------------------------------------------------------------------

    /**
     * AC-ERROR-HANDLING-INVALID-DISTRIBUTION-MODE / E58S02: unknown {@code distributionMode} String
     * is accepted by Jackson (now plain String, not enum) but passes through to registry-membership
     * validation at save/apply time.
     *
     * <p>In E58S02, {@code distributionMode} is a plain String — Jackson does not reject unknown
     * values at deserialization. Registry-membership validation (AC6) rejects unknowns at
     * save/apply time in {@code DraftConfig.validateDistributionModeMembership()}.
     *
     * @see <a href="E51S15">E51S15 — error-handling AC</a>
     * @see <a href="E58S02">E58S02 — String migration: unknown values pass Jackson, rejected at
     *     registry membership validation</a>
     */
    @Test
    void deserialize_withUnknownDistributionMode_deserializesAsString() throws Exception {
        String jsonWithUnknownMode =
                "{\"sectionNumber\":1,\"sortType\":\"team_number\",\"groupCount\":2,"
                        + "\"gameMode\":\"roundRobin\",\"lapBreakTimeMinutes\":0,"
                        + "\"sectionBreakTimeMinutes\":0,\"lapTimeMinutes\":15,\"setQuantity\":1,"
                        + "\"breaks\":[],\"distributionMode\":\"future_unknown\"}";

        // E58S02: unknown distributionMode is now a plain String — Jackson accepts it.
        // Validation occurs at registry membership check time (save/apply).
        DraftSection section = objectMapper.readValue(jsonWithUnknownMode, DraftSection.class);
        assertThat(section.getDistributionMode()).isEqualTo("future_unknown");
    }

    /**
     * AC-ERROR-HANDLING-INVALID-DISTRIBUTION-MODE: {@code validate()} accepts {@code "sequential"}.
     */
    @Test
    void validate_withSequentialDistributionMode_passes() throws Exception {
        String json =
                "{\"sectionNumber\":1,\"sortType\":\"team_number\",\"groupCount\":2,"
                        + "\"gameMode\":\"roundRobin\",\"lapBreakTimeMinutes\":0,"
                        + "\"sectionBreakTimeMinutes\":0,\"lapTimeMinutes\":15,\"setQuantity\":1,"
                        + "\"breaks\":[],\"distributionMode\":\"sequential\"}";
        DraftSection section = objectMapper.readValue(json, DraftSection.class);

        section.validate(); // must not throw
    }

    /**
     * AC-ERROR-HANDLING-INVALID-DISTRIBUTION-MODE: {@code validate()} accepts {@code
     * "round_robin"}.
     */
    @Test
    void validate_withRoundRobinDistributionMode_passes() throws Exception {
        String json =
                "{\"sectionNumber\":1,\"sortType\":\"team_number\",\"groupCount\":2,"
                        + "\"gameMode\":\"roundRobin\",\"lapBreakTimeMinutes\":0,"
                        + "\"sectionBreakTimeMinutes\":0,\"lapTimeMinutes\":15,\"setQuantity\":1,"
                        + "\"breaks\":[],\"distributionMode\":\"round_robin\"}";
        DraftSection section = objectMapper.readValue(json, DraftSection.class);

        section.validate(); // must not throw
    }
}
