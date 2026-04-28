/**
 * Public crypto API for the standalone-worker.
 *
 * <p>Exposes {@link de.vvwt.slotopt.standalone.crypto.ResultSigner} (public interface), {@link
 * de.vvwt.slotopt.standalone.crypto.SubmitResultPayload} (payload record), and {@link
 * de.vvwt.slotopt.standalone.crypto.SigningException} (exception type).
 *
 * <p>The canonical implementation is in {@code crypto.internal}.
 *
 * <p>DEC-35-by-analogy: public surface here; implementation in {@code .internal}. Story: E41S03.
 */
package de.vvwt.slotopt.standalone.crypto;
