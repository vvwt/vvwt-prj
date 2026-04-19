package de.vvwt.dispatcher.identity;

/**
 * HTTP request body for {@code POST /register-key} (AC1 of E01S06).
 *
 * <p>All fields are received as JSON. {@code publicKey} and {@code supersedes} are Base64-encoded
 * (standard Base64, no wrapping) representations of the respective 32-byte raw Ed25519 key
 * material.
 *
 * @param role {@code "worker"} or {@code "submitter"}; required
 * @param publicKey Base64-encoded 32-byte raw Ed25519 public key; required
 * @param supersedes Base64-encoded public key of the key being rotated; optional
 * @param name human-readable label; optional
 */
public record RegisterKeyRequest(String role, String publicKey, String supersedes, String name) {}
