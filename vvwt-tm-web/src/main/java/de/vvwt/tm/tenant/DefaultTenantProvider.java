package de.vvwt.tm.tenant;

import java.util.UUID;

/**
 * Exposes the resolved default-tenant UUID to downstream services.
 *
 * <p>The UUID is resolved at application startup by {@link DefaultTenantBootstrap} before the
 * HTTP server accepts requests. Any bean that needs to scope queries or writes to the default
 * tenant injects this interface and calls {@link #getDefaultTenantId()}.
 *
 * <p>Calls made before bootstrap has completed (i.e. before the {@link DefaultTenantBootstrap}
 * {@link org.springframework.boot.ApplicationRunner} has run) will throw
 * {@link IllegalStateException} — fail-fast, never return null (AC4).
 *
 * @see DefaultTenantBootstrap
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E02S04.story.md">Story E02S04</a>
 */
public interface DefaultTenantProvider {

    /**
     * Returns the resolved default-tenant UUID.
     *
     * @return the default-tenant UUID; never null after bootstrap has completed
     * @throws IllegalStateException if called before bootstrap has completed
     */
    UUID getDefaultTenantId();
}
