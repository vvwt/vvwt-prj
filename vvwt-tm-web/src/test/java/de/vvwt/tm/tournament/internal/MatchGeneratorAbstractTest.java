package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.TeamAvatar;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Abstract contract test for the {@link MatchGenerator} SPI (AC-TDD-MatchGenerator, E21S08).
 *
 * <p>Any concrete {@link MatchGenerator} implementation can extend this class to verify the base
 * contract: null inputs throw {@link IllegalArgumentException}; the result list is non-null; the
 * generator is stateless (multiple calls with the same input return equal results).
 *
 * <p>Source: inventory row 256 — {@code de.vvwt.tm.tournament.MatchGenerator} (promoted to public
 * root package by E33S06 per DEC-35).
 *
 * @see MatchGenerator
 * @see RoundRobinMatchGeneratorTest
 */
public abstract class MatchGeneratorAbstractTest {

    /** Subclasses provide a configured instance of the generator under test. */
    protected abstract MatchGenerator newGenerator();

    /** Creates a minimal {@link Phase} for testing. */
    protected Phase minimalPhase() {
        Phase p = new Phase();
        p.setId(UUID.randomUUID());
        p.setTournamentId(UUID.randomUUID());
        p.setSequenceNumber(1);
        p.setDescription("Test Phase");
        p.setStatus("PENDING");
        p.setCurrentLapNumber(0);
        return p;
    }

    /** Creates a minimal {@link TeamAvatar} with a given ID. */
    protected TeamAvatar minimalAvatar(UUID id) {
        TeamAvatar a = new TeamAvatar();
        a.setId(id);
        a.setPhaseId(UUID.randomUUID());
        return a;
    }

    // -------------------------------------------------------------------------
    // Contract: null phase → IAE
    // -------------------------------------------------------------------------

    @Test
    void generate_nullPhase_throwsIAE() {
        MatchGenerator gen = newGenerator();
        List<TeamAvatar> avatars =
                List.of(minimalAvatar(UUID.randomUUID()), minimalAvatar(UUID.randomUUID()));
        assertThatThrownBy(() -> gen.generate(null, avatars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phase");
    }

    // -------------------------------------------------------------------------
    // Contract: null avatars → IAE
    // -------------------------------------------------------------------------

    @Test
    void generate_nullAvatars_throwsIAE() {
        MatchGenerator gen = newGenerator();
        Phase phase = minimalPhase();
        assertThatThrownBy(() -> gen.generate(phase, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("avatar");
    }

    // -------------------------------------------------------------------------
    // Contract: result is non-null
    // -------------------------------------------------------------------------

    @Test
    void generate_nonNullResult() {
        MatchGenerator gen = newGenerator();
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars =
                List.of(minimalAvatar(UUID.randomUUID()), minimalAvatar(UUID.randomUUID()));
        List<Match> result = gen.generate(phase, avatars);
        assertThat(result).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Contract: stateless — two calls with same input produce same-sized result
    // -------------------------------------------------------------------------

    @Test
    void generate_stateless_sameInputProducesSameSizedResult() {
        MatchGenerator gen = newGenerator();
        Phase phase = minimalPhase();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<TeamAvatar> avatars = List.of(minimalAvatar(id1), minimalAvatar(id2));

        List<Match> first = gen.generate(phase, avatars);
        List<Match> second = gen.generate(phase, avatars);

        assertThat(first).hasSameSizeAs(second);
    }
}
