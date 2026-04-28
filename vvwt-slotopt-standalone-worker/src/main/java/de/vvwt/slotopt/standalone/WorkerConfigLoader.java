package de.vvwt.slotopt.standalone;

/**
 * Parses CLI arguments and returns an immutable {@link WorkerConfig} record.
 *
 * <p>Public interface per DEC-35-by-analogy (E35S04 non-Spring-Modulith precedent): interfaces
 * reside in the public package {@code de.vvwt.slotopt.standalone}; the concrete implementation
 * lives in {@code de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * WorkerConfigLoader loader = new DefaultWorkerConfigLoader();
 * WorkerConfig config = loader.load(args);
 * }</pre>
 *
 * @see de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader
 */
public interface WorkerConfigLoader {

    /**
     * Parses the given CLI argument array and returns a validated {@link WorkerConfig}.
     *
     * <p>If required options ({@code --dispatcher-url}, {@code --key-dir}) are missing, the
     * implementation emits a Picocli usage message to stderr and throws {@link
     * IllegalStateException} containing the non-zero exit code.
     *
     * <p>If {@code --signing-algorithm} is present with a value other than {@code "Ed25519"}, the
     * implementation throws {@link IllegalArgumentException} citing the rejected value, the V1
     * supported set {@code {"Ed25519"}}, and DEC-43 D4.
     *
     * @param args the CLI arguments (typically {@code main(String[] args)} forwarded directly)
     * @return a populated, immutable {@link WorkerConfig}
     * @throws IllegalArgumentException if {@code --signing-algorithm} is an unsupported value
     * @throws IllegalStateException if Picocli reports a non-zero exit (e.g., missing required
     *     option or parse error)
     */
    WorkerConfig load(String[] args);
}
