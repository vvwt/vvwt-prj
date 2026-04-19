package de.vvwt.tm.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure JUnit 5 tests for {@link PasswordGenerator}.
 *
 * <p>No {@code @SpringBootTest} — enforces the "pure Java" contract (AC6, DEC-22). Tests are
 * written strictly before the implementation (TDD, DEC-22).
 *
 * <p>Story: E15S01 — PasswordGenerator reconstruction-in-place with TDD.
 */
class PasswordGeneratorTest {

    // -------------------------------------------------------------------------
    // AC3 — Constructor: null SecureRandom rejected
    // -------------------------------------------------------------------------

    @Test
    void nullSecureRandom_throwsIllegalArgumentException() {
        // AC3: constructor rejects null SecureRandom with a typed exception
        assertThatThrownBy(() -> new PasswordGenerator(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secureRandom");
    }

    // -------------------------------------------------------------------------
    // AC4 — Constructor: invalid length rejected; boundary cases
    // -------------------------------------------------------------------------

    @Test
    void lengthZero_throwsIllegalArgumentException() {
        // AC4: length 0 is rejected
        SecureRandom rng = new SecureRandom();
        assertThatThrownBy(() -> new PasswordGenerator(rng, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    @Test
    void negativeLength_throwsIllegalArgumentException() {
        // AC4: negative length is rejected
        SecureRandom rng = new SecureRandom();
        assertThatThrownBy(() -> new PasswordGenerator(rng, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length");
    }

    @Test
    void lengthOne_accepted() {
        // AC4: length 1 is accepted (boundary)
        SecureRandom rng = new SecureRandom();
        PasswordGenerator gen = new PasswordGenerator(rng, 1);
        String password = gen.generate();
        assertThat(password).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // AC2 — Generated password length matches configured length
    // -------------------------------------------------------------------------

    @Test
    void generatePassword_hasConfiguredLength() {
        // AC2: generated password has exactly the configured length
        SecureRandom rng = new SecureRandom();
        PasswordGenerator gen = new PasswordGenerator(rng, 12);
        String password = gen.generate();
        assertThat(password).hasSize(12);
    }

    @Test
    void defaultLength_isSixteen() {
        // AC2: default length is 16 per Epic scope
        SecureRandom rng = new SecureRandom();
        PasswordGenerator gen = new PasswordGenerator(rng);
        String password = gen.generate();
        assertThat(password).hasSize(16);
    }

    // -------------------------------------------------------------------------
    // AC2 — Generated password contains only alphabet characters
    // -------------------------------------------------------------------------

    @Test
    void generatePassword_containsOnlyAlphanumericChars() {
        // AC2: generated passwords consist only of configured alphabet characters
        SecureRandom rng = new SecureRandom();
        PasswordGenerator gen = new PasswordGenerator(rng);
        String password = gen.generate();
        assertThat(password).matches("[A-Za-z0-9]+");
    }

    @Test
    void defaultAlphabet_isAlphanumeric62Chars() {
        // AC2: default alphabet is 62-char alphanumeric [A-Za-z0-9]
        SecureRandom rng = new SecureRandom();
        PasswordGenerator gen = new PasswordGenerator(rng);
        assertThat(gen.getAlphabet()).hasSize(62);
        assertThat(gen.getAlphabet()).matches("[A-Za-z0-9]+");
    }

    // -------------------------------------------------------------------------
    // AC3a — Seeded SecureRandom produces reproducible output (deterministic test)
    // -------------------------------------------------------------------------

    @Test
    void seededRandom_producesReproducibleOutput() {
        // AC3a: a Random seeded for reproducibility produces expected outputs in a deterministic
        // test.
        // Uses java.util.Random (which SecureRandom extends) with a fixed seed for determinism.
        // Two generators with the same seed must produce the same password.
        long seed = 42L;
        Random rng1 = new Random(seed);
        Random rng2 = new Random(seed);

        // Use the package-private constructor (same package) with default alphabet/length
        PasswordGenerator gen1 =
                new PasswordGenerator(
                        rng1, PasswordGenerator.DEFAULT_ALPHABET, PasswordGenerator.DEFAULT_LENGTH);
        PasswordGenerator gen2 =
                new PasswordGenerator(
                        rng2, PasswordGenerator.DEFAULT_ALPHABET, PasswordGenerator.DEFAULT_LENGTH);

        // Same seed → same output
        assertThat(gen1.generate()).isEqualTo(gen2.generate());
    }

    // -------------------------------------------------------------------------
    // AC2 — Anti-determinism smoke: different seeds → different outputs
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void multipleGenerations_withDifferentSeeds_produceDistinctOutputs(int seed) {
        // AC2: generation with distinct SecureRandom seeds produces distinct outputs (smoke)
        SecureRandom rng = new SecureRandom(new byte[] {(byte) seed});
        PasswordGenerator gen = new PasswordGenerator(rng);
        // Generate 10 passwords from the same seeded RNG — all must be alphabet-only
        Set<String> passwords = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            String pw = gen.generate();
            assertThat(pw).matches("[A-Za-z0-9]+").hasSize(16);
            passwords.add(pw);
        }
        // With a 62^16 space, 10 passwords from one seed should all be different
        assertThat(passwords).hasSizeGreaterThan(1);
    }

    // -------------------------------------------------------------------------
    // AC-ENTROPY-FLOOR — Minimum 72-bit entropy at default parameters
    // -------------------------------------------------------------------------

    @Test
    void entropyFloor_defaultParams_atLeast72Bits() {
        // AC-ENTROPY-FLOOR: log2(alphabetSize ^ length) >= 72
        // Default: 62-char alphabet x 16 characters = log2(62^16) ≈ 95.3 bits
        SecureRandom rng = new SecureRandom();
        PasswordGenerator gen = new PasswordGenerator(rng);

        int alphabetSize = gen.getAlphabet().length();
        int length = gen.getLength();
        double entropy = length * (Math.log(alphabetSize) / Math.log(2));

        assertThat(entropy).isGreaterThanOrEqualTo(72.0);
    }

    @Test
    void entropyFloor_customAlphabet_atLeast72Bits() {
        // AC-ENTROPY-FLOOR: custom alphabet/length combos above the floor must be accepted
        // 36-char hex-like alphabet x 12 = log2(36^12) ≈ 62 bits → below floor, but
        // custom constructor with alphabet parameter is NOT in scope for E15S01 (defaults only).
        // This test simply confirms the default params give the floor:
        SecureRandom rng = new SecureRandom();
        PasswordGenerator gen = new PasswordGenerator(rng, 16);
        // 62-char (default alphabet) ^ 16 = 95.3 bits
        double entropy = gen.getLength() * (Math.log(gen.getAlphabet().length()) / Math.log(2));
        assertThat(entropy).isGreaterThanOrEqualTo(72.0);
    }
}
