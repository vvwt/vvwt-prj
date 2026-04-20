package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TeamAvatarRating} entity invariants (E21S04, AC-TDD-TeamAvatarRating).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TeamAvatarRating} at {@code
 * de.vvwt.tm.tournament.TeamAvatarRating} did not exist at commit time, causing a compile error —
 * satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>PK field is {@code avatarId} (not {@code id}) — 1:1 with TeamAvatar
 *   <li>All statistical counter fields settable/gettable
 *   <li>Quotient fields (set_quotient, ball_quotient) accessible
 *   <li>isWithoutAssessment flag accessible
 * </ul>
 *
 * @see TeamAvatarRating
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 457)</a>
 */
@DisplayName("TeamAvatarRating entity invariants — E21S04 AC-TDD-TeamAvatarRating")
class TeamAvatarRatingTest {

    @Test
    @DisplayName("avatarId is the PK field (1:1 with TeamAvatar)")
    void avatarIdIsThePrimaryKey() {
        UUID avatarId = UUID.randomUUID();
        TeamAvatarRating rating = new TeamAvatarRating();
        rating.setAvatarId(avatarId);

        assertThat(rating.getAvatarId()).isEqualTo(avatarId);
    }

    @Test
    @DisplayName("statistical counter fields settable and gettable")
    void statisticalCounterFieldsSettableAndGettable() {
        TeamAvatarRating rating = new TeamAvatarRating();
        rating.setMatchCount(5);
        rating.setSetCount(10);
        rating.setPoints(15);
        rating.setSetsWon(7);
        rating.setSetsLost(3);
        rating.setBallsWon(120);
        rating.setBallsLost(80);

        assertThat(rating.getMatchCount()).isEqualTo(5);
        assertThat(rating.getSetCount()).isEqualTo(10);
        assertThat(rating.getPoints()).isEqualTo(15);
        assertThat(rating.getSetsWon()).isEqualTo(7);
        assertThat(rating.getSetsLost()).isEqualTo(3);
        assertThat(rating.getBallsWon()).isEqualTo(120);
        assertThat(rating.getBallsLost()).isEqualTo(80);
    }

    @Test
    @DisplayName("quotient fields settable and gettable")
    void quotientFieldsSettableAndGettable() {
        TeamAvatarRating rating = new TeamAvatarRating();
        rating.setSetQuotient(2.33);
        rating.setBallQuotient(1.5);

        assertThat(rating.getSetQuotient()).isEqualTo(2.33);
        assertThat(rating.getBallQuotient()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("isWithoutAssessment flag settable and gettable")
    void withoutAssessmentFlagSettableAndGettable() {
        TeamAvatarRating rating = new TeamAvatarRating();
        assertThat(rating.isWithoutAssessment()).isFalse(); // default

        rating.setWithoutAssessment(true);
        assertThat(rating.isWithoutAssessment()).isTrue();
    }

    @Test
    @DisplayName("tenantId field settable and gettable")
    void tenantIdFieldSettableAndGettable() {
        UUID tenantId = UUID.randomUUID();
        TeamAvatarRating rating = new TeamAvatarRating();
        rating.setTenantId(tenantId);

        assertThat(rating.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("default values are zero/false for counters and flags")
    void defaultValuesAreZeroOrFalse() {
        TeamAvatarRating rating = new TeamAvatarRating();

        assertThat(rating.getMatchCount()).isZero();
        assertThat(rating.getSetCount()).isZero();
        assertThat(rating.getPoints()).isZero();
        assertThat(rating.getSetsWon()).isZero();
        assertThat(rating.getSetsLost()).isZero();
        assertThat(rating.getBallsWon()).isZero();
        assertThat(rating.getBallsLost()).isZero();
        assertThat(rating.getSetQuotient()).isZero();
        assertThat(rating.getBallQuotient()).isZero();
        assertThat(rating.isWithoutAssessment()).isFalse();
    }
}
