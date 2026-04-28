/**
 * Public audio sub-package of the {@code timer} bounded context.
 *
 * <p>This sub-package is a named interface of the {@code timer} Modulith module (DEC-21 + DEC-35).
 * It is exposed as {@code timer::audio} per empirical E26S02 delivery finding: Spring Modulith 2.x
 * treats sub-packages as separate named modules — cross-module consumers reaching {@code
 * timer.audio.*} types MUST declare {@code "timer::audio"} as a named interface.
 *
 * <p>Types in this package ({@link de.vvwt.tm.timer.audio.AudioStorageService}, {@link
 * de.vvwt.tm.timer.audio.AudioCategory}, {@link de.vvwt.tm.timer.audio.AudioFileMetadata}, and
 * exception types) are consumable by other Modulith modules via the {@code timer::audio} named
 * interface declaration.
 *
 * <p>Implementation types live in {@code de.vvwt.tm.timer.audio.internal} and MUST NOT be accessed
 * by any other module.
 *
 * @since E26S02
 */
@org.springframework.modulith.NamedInterface("audio")
package de.vvwt.tm.timer.audio;
