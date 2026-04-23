package de.vvwt.tm.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.photo.internal.DefaultPhotoUrlBuilder;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Port-contract test for {@link PhotoUrlBuilder} (E23S02, DEC-36, DEC-41).
 *
 * <p>Located at {@code de.vvwt.tm.photo.PhotoUrlBuilderTest} — a cross-package test relative to
 * {@code de.vvwt.tm.photo.internal.DefaultPhotoUrlBuilder} per DEC-36 (cross-package tests MUST
 * reference and mock the public interface only, never the concrete implementation class). The field
 * declaration uses {@link PhotoUrlBuilder} interface type throughout.
 *
 * <p>Written RED-first before {@link PhotoUrlBuilder} and {@link DefaultPhotoUrlBuilder} existed
 * (DEC-22 Iron Law). Initial compilation failure against absent production types demonstrates the
 * RED state per AC-TESTING-RED-FIRST.
 *
 * <h2>Test obligations (DEC-41 hierarchy)</h2>
 *
 * <ol>
 *   <li>(MANDATORY) New TDD tests for new code — all tests in this class satisfy this obligation.
 * </ol>
 *
 * <h2>Observable-form classification (DEC-41 criterion)</h2>
 *
 * <p>{@link #buildTeamPhotoUrl_conformsToUrlTemplate} satisfies DEC-41 criterion (a): jqwik
 * {@code @Property} annotation present — quantified algebraic/structural property over generated
 * inputs. The invariant is named in the method name and the test body quantifies over all
 * representative UUID inputs.
 *
 * @see PhotoUrlBuilder
 * @since E23S02
 */
class PhotoUrlBuilderTest {

    /**
     * Subject under test — typed as the public interface per DEC-36 (cross-package rule).
     *
     * <p>Field initializer used (rather than {@code @BeforeEach}) to ensure the instance is
     * available in both JUnit 5 {@code @Test} methods and jqwik {@code @Property} methods. jqwik
     * creates its own test instance per property run and does not honour JUnit 5 lifecycle
     * callbacks ({@code @BeforeEach} is not invoked by the jqwik engine).
     */
    private final PhotoUrlBuilder builder = new DefaultPhotoUrlBuilder();

    // -------------------------------------------------------------------------
    // AC-TESTING-OBSERVABLE-FORM-QUANTIFIED — jqwik @Property (DEC-41 criterion a)
    // Invariant: buildTeamPhotoUrl(x, y) == "/api/tournaments/" + x + "/teams/" + y + "/photo"
    // for all non-null UUID pairs (x, y).
    // -------------------------------------------------------------------------

    /**
     * Invariant: {@code buildTeamPhotoUrl(x, y)} produces the canonical photo URL template for all
     * non-null UUID inputs.
     *
     * <p>Satisfies AC-TESTING-OBSERVABLE-FORM-QUANTIFIED via jqwik {@code @Property} (DEC-41
     * criterion a). The algebraic invariant is named in this method name and the assertion body
     * quantifies over all generated UUID pairs.
     */
    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.of(
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    @Property
    void buildTeamPhotoUrl_conformsToUrlTemplate(
            @ForAll("uuids") UUID tournamentId, @ForAll("uuids") UUID teamId) {
        String expected = "/api/tournaments/" + tournamentId + "/teams/" + teamId + "/photo";
        assertThat(builder.buildTeamPhotoUrl(tournamentId, teamId)).isEqualTo(expected);
    }

    // -------------------------------------------------------------------------
    // AC-TESTING-EDGE-CASES — all-zeros UUID pair
    // Edge cases are in addition to the @Property invariant check, not a substitute.
    // -------------------------------------------------------------------------

    @Test
    void buildTeamPhotoUrl_allZerosUuidPair_conformsToTemplate() {
        UUID zero = new UUID(0L, 0L);
        String expected = "/api/tournaments/" + zero + "/teams/" + zero + "/photo";
        assertThat(builder.buildTeamPhotoUrl(zero, zero)).isEqualTo(expected);
    }

    // -------------------------------------------------------------------------
    // AC-ERROR-HANDLING — NullPointerException on null arguments
    // All three null-argument combinations must throw NullPointerException.
    // -------------------------------------------------------------------------

    @Test
    void buildTeamPhotoUrl_nullTournamentId_throwsNpe() {
        UUID teamId = UUID.randomUUID();
        assertThatThrownBy(() -> builder.buildTeamPhotoUrl(null, teamId))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildTeamPhotoUrl_nullTeamId_throwsNpe() {
        UUID tournamentId = UUID.randomUUID();
        assertThatThrownBy(() -> builder.buildTeamPhotoUrl(tournamentId, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void buildTeamPhotoUrl_bothNull_throwsNpe() {
        assertThatThrownBy(() -> builder.buildTeamPhotoUrl(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
