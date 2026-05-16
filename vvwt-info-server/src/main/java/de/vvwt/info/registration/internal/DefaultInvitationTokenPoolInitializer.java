package de.vvwt.info.registration.internal;

import de.vvwt.info.registration.InvitationTokenPool;
import de.vvwt.info.registration.InvitationTokenPoolInitializer;
import jakarta.annotation.PostConstruct;

/**
 * Thin initializer bean that calls {@link InvitationTokenPool#initialize()} once the application
 * context is ready (E38S04 AC10).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 */
public class DefaultInvitationTokenPoolInitializer implements InvitationTokenPoolInitializer {

    private final InvitationTokenPool pool;

    public DefaultInvitationTokenPoolInitializer(InvitationTokenPool pool) {
        this.pool = pool;
    }

    @Override
    @PostConstruct
    public void init() {
        pool.initialize();
    }
}
