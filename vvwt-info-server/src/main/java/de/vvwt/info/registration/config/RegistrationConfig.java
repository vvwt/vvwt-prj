package de.vvwt.info.registration.config;

import de.vvwt.info.crypto.SignatureVerifier;
import de.vvwt.info.crypto.SignatureVerifierRegistry;
import de.vvwt.info.crypto.internal.Ed25519SignatureVerifier;
import de.vvwt.info.persistence.algorithm.AlgorithmRegistryDao;
import de.vvwt.info.persistence.audit.AuditLogDao;
import de.vvwt.info.persistence.invitation.ConsumedInvitationTokenDao;
import de.vvwt.info.persistence.tenant.TenantDao;
import de.vvwt.info.registration.InvitationTokenPool;
import de.vvwt.info.registration.RegistrationController;
import de.vvwt.info.registration.RegistrationService;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the registration subsystem (AC5, AC6, AC10).
 *
 * <p>Wires:
 *
 * <ul>
 *   <li>{@link Ed25519SignatureVerifier} as the Ed25519 implementation (DEC-43 D4)
 *   <li>{@link SignatureVerifierRegistry} with Ed25519 as the V1 algorithm
 *   <li>{@link InvitationTokenPool} for primary-profile invitation-token durability (AC10)
 *   <li>{@link RegistrationService} with all dependencies
 *   <li>{@link RegistrationController}
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC5, AC6,
 *     AC10</a>
 */
@Configuration
@EnableConfigurationProperties(RegistrationProperties.class)
public class RegistrationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Ed25519SignatureVerifier ed25519SignatureVerifier() {
        return new Ed25519SignatureVerifier();
    }

    @Bean
    public SignatureVerifierRegistry signatureVerifierRegistry(Ed25519SignatureVerifier ed25519) {
        return new SignatureVerifierRegistry(Map.<String, SignatureVerifier>of("Ed25519", ed25519));
    }

    @Bean
    public InvitationTokenPool invitationTokenPool(
            RegistrationProperties props,
            ConsumedInvitationTokenDao consumedTokenDao,
            Clock clock) {
        return new InvitationTokenPool(props, consumedTokenDao, clock);
    }

    @Bean
    public RegistrationService registrationService(
            AlgorithmRegistryDao algorithmRegistryDao,
            TenantDao tenantDao,
            AuditLogDao auditLogDao,
            InvitationTokenPool tokenPool,
            RegistrationProperties props,
            Clock clock) {
        return new RegistrationService(
                algorithmRegistryDao, tenantDao, auditLogDao, tokenPool, props, clock);
    }

    @Bean
    public RegistrationController registrationController(RegistrationService registrationService) {
        return new RegistrationController(registrationService);
    }

    /** Initializes the invitation token pool after the Spring context is ready. */
    @Bean
    public InvitationTokenPoolInitializer invitationTokenPoolInitializer(InvitationTokenPool pool) {
        return new InvitationTokenPoolInitializer(pool);
    }

    /**
     * Thin initializer bean that calls {@link InvitationTokenPool#initialize()} once the
     * application context is ready.
     */
    public static class InvitationTokenPoolInitializer {
        private final InvitationTokenPool pool;

        public InvitationTokenPoolInitializer(InvitationTokenPool pool) {
            this.pool = pool;
        }

        @PostConstruct
        public void init() {
            pool.initialize();
        }
    }
}
