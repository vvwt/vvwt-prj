/**
 * Internal crypto implementations for the standalone-worker.
 *
 * <p>Contains {@link de.vvwt.slotopt.standalone.crypto.internal.DefaultResultSigner} — the
 * canonical implementation of {@link de.vvwt.slotopt.standalone.crypto.ResultSigner}.
 *
 * <p>DEC-35-by-analogy: types in this package are not part of the public API. Cross-module
 * consumers MUST reference the public interface in {@code de.vvwt.slotopt.standalone.crypto}.
 *
 * <p>Story: E41S03.
 */
package de.vvwt.slotopt.standalone.crypto.internal;
