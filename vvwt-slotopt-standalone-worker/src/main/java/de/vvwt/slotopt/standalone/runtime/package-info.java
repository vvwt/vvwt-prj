/**
 * Public runtime API for the vvwt-slotopt-standalone-worker process.
 *
 * <p>Interfaces ({@link de.vvwt.slotopt.standalone.runtime.WorkerLoop}, {@link
 * de.vvwt.slotopt.standalone.runtime.CpuThrottle}) and shared exceptions ({@link
 * de.vvwt.slotopt.standalone.runtime.WorkerLoopException}) reside in this package (public surface,
 * per DEC-35-by-analogy). Implementations live in {@code runtime.internal}.
 *
 * <p>Story: E41S05.
 */
package de.vvwt.slotopt.standalone.runtime;
