package de.vvwt.tm.infoportal;

import de.vvwt.tm.infoportal.internal.DefaultEd25519KeypairManager;
import de.vvwt.tm.infoportal.internal.DefaultInfoPortalPublisherService;
import de.vvwt.tm.infoportal.internal.DefaultTmJcsCanonicalizer;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Spring wiring for the Info Portal publisher integration (AC6, AC16).
 *
 * <p>All beans are conditional on {@code info-portal.url} being set. When the property is absent,
 * no RestTemplate, KeypairManager, PublisherService, or StatusController beans are created, and the
 * publisher feature is entirely inactive.
 *
 * <p>The {@link Ed25519KeypairManager} is initialized on bean creation. If key files already exist
 * on disk (e.g. from a previous run) they are loaded; otherwise a new keypair is generated and
 * persisted with AES-256-GCM encryption (AC8 NO-PLAINTEXT-ON-DISK).
 *
 * <p>DEC-58 Clause A + DEC-72 Clause A-ext: all {@code @Bean} factory methods return the public
 * interface type ({@link Ed25519KeypairManager}, {@link TmJcsCanonicalizer}, {@link
 * InfoPortalPublisherService}) rather than the concrete implementation — so that injection sites
 * receive the interface, enabling JDK proxying and Mockito mockability.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC6, AC8</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-6.md">DEC-6 —
 *     asymmetric-key registration</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-43.md">DEC-43 —
 *     algorithm agility</a>
 */
@Configuration
@EnableConfigurationProperties(InfoPortalProperties.class)
@ConditionalOnProperty(prefix = "info-portal", name = "url")
public class InfoPortalConfig {

    private static final Logger log = LoggerFactory.getLogger(InfoPortalConfig.class);

    /**
     * Dedicated {@link RestTemplate} for info-portal outbound calls.
     *
     * <p>Separate instance (not the shared application RestTemplate) so that per-feature
     * interceptors or timeouts can be tuned independently.
     */
    @Bean
    public RestTemplate infoPortalRestTemplate() {
        return new RestTemplate();
    }

    /**
     * Ed25519 keypair manager with AES-256-GCM encrypted-at-rest private key (AC8).
     *
     * <p>Key files are stored in {@code info-portal.keypair-dir} (default: {@code
     * ${user.home}/.tournament-manager/info-portal-keys}). On first startup, a new keypair is
     * generated and encrypted before being written to disk. On subsequent startups, the existing
     * encrypted keypair is loaded.
     *
     * @return the {@link Ed25519KeypairManager} interface (DEC-72 Clause A-ext)
     */
    @Bean
    public Ed25519KeypairManager ed25519KeypairManager(InfoPortalProperties properties)
            throws GeneralSecurityException, IOException {
        Path storageDir = Path.of(properties.getKeypairDir());
        DefaultEd25519KeypairManager manager = new DefaultEd25519KeypairManager(storageDir);
        manager.initializeIfAbsent();
        log.info("[InfoPortal] Ed25519 keypair manager initialized (storageDir={})", storageDir);
        return manager;
    }

    /**
     * JCS canonicalizer (RFC 8785) for publisher request signing (AC9).
     *
     * @return the {@link TmJcsCanonicalizer} interface (DEC-72 Clause A-ext)
     */
    @Bean
    public TmJcsCanonicalizer tmJcsCanonicalizer() {
        return new DefaultTmJcsCanonicalizer();
    }

    /**
     * Core publisher service — activated only when {@code info-portal.url} is set (AC6).
     *
     * @return the {@link InfoPortalPublisherService} interface (DEC-72 Clause A-ext)
     */
    @Bean
    public InfoPortalPublisherService infoPortalPublisherService(
            InfoPortalProperties properties,
            InfoPortalStateDao stateDao,
            RestTemplate infoPortalRestTemplate,
            Ed25519KeypairManager ed25519KeypairManager,
            TmJcsCanonicalizer tmJcsCanonicalizer) {
        return new DefaultInfoPortalPublisherService(
                properties,
                stateDao,
                infoPortalRestTemplate,
                ed25519KeypairManager,
                tmJcsCanonicalizer);
    }
}
