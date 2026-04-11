package de.vvwt.dispatcher.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link KeyRegistration}.
 *
 * <p>See Story E01S06 AC1–AC4.
 */
public interface KeyRegistrationRepository extends JpaRepository<KeyRegistration, UUID> {

    /**
     * Finds all registrations matching the given raw public key bytes.
     *
     * <p>Used to detect idempotent re-registration (AC4) and role conflicts (AC4).
     * Typically returns 0 or 1 result; may return 2 if the same key was registered
     * for both roles (corner case handled in service logic).
     *
     * @param publicKeyBytes raw 32-byte Ed25519 public key
     * @return all matching registrations (across all roles)
     */
    List<KeyRegistration> findAllByPublicKeyBytes(byte[] publicKeyBytes);

    /**
     * Finds an active registration by public key bytes and role.
     *
     * <p>Used during key rotation ({@code supersedes} lookup) to find the existing
     * key to supersede (AC3).
     *
     * @param publicKeyBytes raw 32-byte Ed25519 public key
     * @param role           {@code "worker"} or {@code "submitter"}
     * @return the matching registration, if any
     */
    Optional<KeyRegistration> findByPublicKeyBytesAndRole(byte[] publicKeyBytes, String role);
}
