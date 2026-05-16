// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.auth.internal;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Pure-Java, Spring-free password generator for admin credentials.
 *
 * <p>Generates cryptographically random alphanumeric passwords using a configured {@link
 * SecureRandom} instance. The default alphabet is {@code [A-Za-z0-9]} (62 characters) and the
 * default length is 16, yielding ~95.3 bits of entropy — well above the 72-bit floor required by
 * E15S01 AC-ENTROPY-FLOOR (preserved from E05S02 AC11).
 *
 * <h2>Design constraints (DEC-21, DEC-22)</h2>
 *
 * <ul>
 *   <li>Located in {@code de.vvwt.tm.auth.internal} — implementation-private to the {@code auth}
 *       bounded context; not part of the public API (DEC-21).
 *   <li>No Spring annotations ({@code @Component}, {@code @Service}) — plain POJO instantiated by
 *       the Spring configuration class in E15S04 (AC5).
 *   <li>Written test-first per the TDD Iron Law (DEC-22): see {@code PasswordGeneratorTest} for the
 *       corresponding tests.
 * </ul>
 *
 * <h2>Constructor injection</h2>
 *
 * <p>{@link SecureRandom} is injected via constructor for testability. The production wiring
 * (E15S04) uses {@code new SecureRandom()} (default, seeded by the JVM). Tests may supply a seeded
 * {@link Random} for deterministic verification (AC3a); since {@link SecureRandom} extends {@link
 * Random}, production callers pass a {@code SecureRandom} and test callers may pass a deterministic
 * {@code new Random(seed)}.
 *
 * <h2>Entropy</h2>
 *
 * <p>For the defaults: {@code log2(62^16) = 16 * log2(62) ≈ 95.3 bits}. The 72-bit floor means a
 * minimum viable configuration is alphabet=62, length=12 ({@code 12 * 5.954 ≈ 71.5 bits}, which is
 * below floor) — the default (16) keeps a comfortable margin.
 *
 * @see de.vvwt.tm.auth.internal.PasswordGeneratorTest
 * @since E15S01
 */
public final class PasswordGenerator {

    /**
     * Default alphabet: uppercase, lowercase, and digits — 62 characters. Alphanumeric per Epic E15
     * scope; no ambiguous characters (0/O, l/1/I are present but tolerated — they are log-safe and
     * the admin sees this only at first start).
     */
    static final String DEFAULT_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** Default password length: 16 characters → ~95.3 bits of entropy. */
    static final int DEFAULT_LENGTH = 16;

    private final String alphabet;
    private final int length;
    private final Random random;

    /**
     * Constructs a {@code PasswordGenerator} with the default alphabet (62-char alphanumeric) and
     * default length (16), using the provided {@link SecureRandom}.
     *
     * <p>Production callers pass {@code new SecureRandom()} here. Tests may pass a seeded {@link
     * SecureRandom} or a plain {@link java.util.Random} for deterministic output.
     *
     * @param secureRandom the cryptographic random source; must not be {@code null}
     * @throws IllegalArgumentException if {@code secureRandom} is {@code null}
     */
    public PasswordGenerator(SecureRandom secureRandom) {
        this((Random) secureRandom, DEFAULT_ALPHABET, DEFAULT_LENGTH);
    }

    /**
     * Constructs a {@code PasswordGenerator} with the default alphabet (62-char alphanumeric) and a
     * configured length, using the provided {@link SecureRandom}.
     *
     * @param secureRandom the cryptographic random source; must not be {@code null}
     * @param length the number of characters to generate; must be &gt;= 1
     * @throws IllegalArgumentException if {@code secureRandom} is {@code null} or {@code length} is
     *     less than 1
     */
    public PasswordGenerator(SecureRandom secureRandom, int length) {
        this((Random) secureRandom, DEFAULT_ALPHABET, length);
    }

    /**
     * Package-private constructor for testability: accepts a {@link Random} instance (which {@link
     * SecureRandom} extends), allowing tests to pass a deterministically seeded {@link
     * java.util.Random} for reproducible test output (AC3a).
     *
     * @param random the random source; must not be {@code null}
     * @param alphabet the character set; must not be {@code null} or empty
     * @param length password length; must be &gt;= 1
     */
    PasswordGenerator(Random random, String alphabet, int length) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "secureRandom must not be null — use new SecureRandom() for production");
        }
        if (alphabet == null || alphabet.isEmpty()) {
            throw new IllegalArgumentException("alphabet must not be null or empty");
        }
        if (length < 1) {
            throw new IllegalArgumentException("length must be >= 1 but was: " + length);
        }
        this.random = random;
        this.alphabet = alphabet;
        this.length = length;
    }

    /**
     * Generates a cryptographically random password of the configured length, using characters
     * drawn uniformly from the configured alphabet.
     *
     * @return a non-null password string of exactly {@link #getLength()} characters, each drawn
     *     from {@link #getAlphabet()}
     */
    public String generate() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /**
     * Returns the configured alphabet.
     *
     * @return the alphabet string; never {@code null} or empty
     */
    public String getAlphabet() {
        return alphabet;
    }

    /**
     * Returns the configured password length.
     *
     * @return the password length; always &gt;= 1
     */
    public int getLength() {
        return length;
    }
}
