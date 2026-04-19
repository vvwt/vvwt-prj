package de.vvwt.tm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for device registration limits (E07S02 AC2).
 *
 * <p>Bound to the {@code vvwt.devices} property namespace.
 *
 * <p>Example {@code application.yml} override:
 *
 * <pre>
 * vvwt:
 *   devices:
 *     max-display-count: 5
 * </pre>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S02.story.md">Story
 *     E07S02</a>
 */
@Component
@ConfigurationProperties(prefix = "vvwt.devices")
public class DeviceLimitConfig {

    /**
     * Maximum number of DISPLAY devices that can be registered per tenant+location (AC2). Defaults
     * to 10.
     */
    private int maxDisplayCount = 10;

    public int getMaxDisplayCount() {
        return maxDisplayCount;
    }

    public void setMaxDisplayCount(int maxDisplayCount) {
        this.maxDisplayCount = maxDisplayCount;
    }
}
