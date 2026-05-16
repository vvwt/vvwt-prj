package de.vvwt.info.registration;

/**
 * Thin initializer that calls {@link InvitationTokenPool#initialize()} once the application context
 * is ready (E38S04 AC10).
 *
 * @see de.vvwt.info.registration.internal.DefaultInvitationTokenPoolInitializer
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC10</a>
 */
public interface InvitationTokenPoolInitializer {

    /** Initializes the invitation token pool at context startup. */
    void init();
}
